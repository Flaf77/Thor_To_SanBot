package com.thorbridge.sanbot.net;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** One Thor control connection: newline-delimited JSON in both directions. */
public class ControlSession {

    public interface Listener {
        void onMessage(ControlSession s, JSONObject msg);

        void onClosed(ControlSession s);
    }

    private static final int MAX_LINE = 1024 * 1024;

    public final Socket socket;
    /** "server" = Thor connected to the tablet, "client" = the tablet connected to Thor. */
    public final String mode;
    public final long since = System.currentTimeMillis();
    public volatile boolean authenticated;
    public volatile int stateRateHz;
    public volatile long lastStateSentAt;
    public volatile long lastRxAt = System.currentTimeMillis();
    public volatile long msgsIn, msgsOut;

    private final Listener listener;
    private final QueuedSocketWriter writer;
    private volatile boolean closed;

    public ControlSession(Socket socket, String mode, int stateRateHz, boolean authenticated, Listener listener) throws IOException {
        this.socket = socket;
        this.mode = mode;
        this.stateRateHz = stateRateHz;
        this.authenticated = authenticated;
        this.listener = listener;
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(60000);
        writer = new QueuedSocketWriter(socket, 1024, "ctrl-tx-" + remote(), w -> close());
        Thread t = new Thread(this::readLoop, "ctrl-rx-" + remote());
        t.setDaemon(true);
        t.start();
    }

    public String remote() {
        return socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
    }

    public String remoteIp() {
        return socket.getInetAddress().getHostAddress();
    }

    public boolean isOpen() {
        return !closed;
    }

    public void send(JSONObject o) {
        if (closed) return;
        if (writer.offer((o.toString() + "\n").getBytes(StandardCharsets.UTF_8))) msgsOut++;
    }

    private void readLoop() {
        try {
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream line = new ByteArrayOutputStream(1024);
            byte[] buf = new byte[8192];
            int n;
            while (!closed && (n = in.read(buf)) > 0) {
                lastRxAt = System.currentTimeMillis();
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    if (b == '\n') {
                        handleLine(line.toString("UTF-8").trim());
                        line.reset();
                    } else {
                        line.write(b);
                        if (line.size() > MAX_LINE) throw new IOException("line too long");
                    }
                }
            }
        } catch (IOException ignored) {
        } finally {
            close();
        }
    }

    private void handleLine(String s) {
        if (s.isEmpty()) return;
        msgsIn++;
        JSONObject o;
        try {
            o = new JSONObject(s);
        } catch (Exception e) {
            send(errorMsg("invalid JSON: " + e.getMessage()));
            return;
        }
        listener.onMessage(this, o);
    }

    private static JSONObject errorMsg(String text) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "error");
            o.put("error", text);
        } catch (Exception ignored) {
        }
        return o;
    }

    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        if (writer != null) writer.close();
        listener.onClosed(this);
    }
}
