package com.thorbridge.sanbot.net;

import android.graphics.Bitmap;
import android.net.Uri;

import com.thorbridge.sanbot.App;
import com.thorbridge.sanbot.EventLog;
import com.thorbridge.sanbot.robot.SanbotRobot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal read-only HTTP server:
 *   /                  status page
 *   /state.json        all sensor + motor values
 *   /info.json         same content as the "hello" message
 *   /camera/{id}.mjpg  MJPEG stream of an Android camera (works with OpenCV / browsers)
 *   /camera/{id}.jpg   single JPEG
 *   /hd.jpg            snapshot of the HD head camera (Sanbot SDK)
 * When a token is configured, add ?token=... or connect from a host with an authenticated control session.
 */
class HttpServer {

    private static final Pattern CAM = Pattern.compile("^/camera/(\\d+)\\.(mjpg|jpg)$");
    private static final String BOUNDARY = "sanbotframe";

    private final App app;
    private final Bridge bridge;
    private final AtomicInteger mjpeg = new AtomicInteger();
    private ServerSocket server;
    private volatile boolean running;

    HttpServer(App app, Bridge bridge) {
        this.app = app;
        this.bridge = bridge;
    }

    int mjpegClients() {
        return mjpeg.get();
    }

    synchronized void start(int port) {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
            running = true;
            final ServerSocket ss = server;
            Thread t = new Thread(() -> {
                while (running && !ss.isClosed()) {
                    try {
                        final Socket s = ss.accept();
                        new Thread(() -> handle(s), "http-conn").start();
                    } catch (IOException e) {
                        if (running) EventLog.e("http", "accept failed", e);
                    }
                }
            }, "http-accept");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            EventLog.e("http", "cannot listen on port " + port, e);
        }
    }

    synchronized void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            server = null;
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, last4 = 0;
        while ((c = in.read()) != -1) {
            b.write(c);
            if (b.size() > 8192) throw new IOException("header too large");
            last4 = (last4 << 8) | (c & 0xFF);
            if (last4 == 0x0D0A0D0A || (last4 & 0xFFFF) == 0x0A0A) break;
        }
        return b.toString("US-ASCII");
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            String head = readHead(s.getInputStream());
            String first = head.split("\r?\n", 2)[0];
            String[] parts = first.split(" ");
            OutputStream out = s.getOutputStream();
            if (parts.length < 2) {
                send(out, 400, "text/plain", "bad request".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!"GET".equals(parts[0])) {
                send(out, 405, "text/plain", "only GET".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Uri uri = Uri.parse("http://x" + parts[1]);
            String path = uri.getPath() == null ? "/" : uri.getPath();
            String ip = s.getInetAddress().getHostAddress();
            if (bridge.authRequired() && !bridge.isIpAllowed(ip) && !bridge.checkToken(uri.getQueryParameter("token"))) {
                send(out, 401, "text/plain", "token required (?token=...)".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Matcher m = CAM.matcher(path);
            if ("/".equals(path)) {
                send(out, 200, "text/html; charset=utf-8", indexHtml().getBytes(StandardCharsets.UTF_8));
            } else if ("/state.json".equals(path)) {
                send(out, 200, "application/json", app.state().toJson().toString(2).getBytes(StandardCharsets.UTF_8));
            } else if ("/info.json".equals(path)) {
                send(out, 200, "application/json", bridge.helloInfo().toString(2).getBytes(StandardCharsets.UTF_8));
            } else if ("/hd.jpg".equals(path)) {
                SanbotRobot r = app.robot();
                Bitmap b = r == null ? null : r.hdSnapshot();
                if (b == null) {
                    send(out, 503, "text/plain", "no HD frame (open the HD preview or connect to the H.264 port first)".getBytes(StandardCharsets.UTF_8));
                } else {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    b.compress(Bitmap.CompressFormat.JPEG, 80, bos);
                    send(out, 200, "image/jpeg", bos.toByteArray());
                }
            } else if (m.matches()) {
                int id = Integer.parseInt(m.group(1));
                if ("jpg".equals(m.group(2))) singleJpeg(out, id);
                else mjpegStream(out, id);
            } else {
                send(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void singleJpeg(OutputStream out, int id) throws Exception {
        long seq = app.cameras().jpegSeq();
        app.cameras().acquire(id);
        try {
            Object[] f = app.cameras().waitJpeg(seq, 5000);
            if (f == null) send(out, 503, "text/plain", "camera not available".getBytes(StandardCharsets.UTF_8));
            else send(out, 200, "image/jpeg", (byte[]) f[1]);
        } finally {
            app.cameras().release();
        }
    }

    private void mjpegStream(OutputStream out, int id) throws Exception {
        app.cameras().acquire(id);
        mjpeg.incrementAndGet();
        try {
            out.write(("HTTP/1.0 200 OK\r\nCache-Control: no-cache\r\nConnection: close\r\n"
                    + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            long seq = app.cameras().jpegSeq();
            while (running) {
                Object[] f = app.cameras().waitJpeg(seq, 5000);
                if (f == null) continue;
                seq = (Long) f[0];
                byte[] jpg = (byte[]) f[1];
                out.write(("--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpg.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(jpg);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } finally {
            mjpeg.decrementAndGet();
            app.cameras().release();
        }
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found" : "Error";
        out.write(("HTTP/1.0 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String indexHtml() {
        StringBuilder cams = new StringBuilder();
        for (com.thorbridge.sanbot.media.AndroidCameraHub.Info c : app.cameras().list()) {
            cams.append("<li><a href=\"/camera/").append(c.id).append(".mjpg\">").append(esc(c.toString())).append(" MJPEG</a></li>");
        }
        return "<!doctype html><html><head><meta charset=utf-8><title>" + esc(app.prefs().robotName()) + " bridge</title>"
                + "<style>body{font-family:sans-serif;background:#111;color:#eee}pre{background:#222;padding:8px}</style></head><body>"
                + "<h2>Sanbot Thor Bridge - " + esc(app.prefs().robotName()) + "</h2>"
                + "<ul><li><a href=\"/state.json\">state.json</a></li><li><a href=\"/info.json\">info.json</a></li>"
                + "<li><a href=\"/hd.jpg\">HD head camera snapshot</a></li>" + cams + "</ul>"
                + "<pre id=s>loading...</pre><script>"
                + "var q=location.search;setInterval(function(){fetch('/state.json'+q).then(function(r){return r.text()})"
                + ".then(function(t){document.getElementById('s').textContent=t})},1000)</script></body></html>";
    }
}
