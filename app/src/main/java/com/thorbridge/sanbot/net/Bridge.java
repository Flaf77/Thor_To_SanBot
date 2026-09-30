package com.thorbridge.sanbot.net;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import com.thorbridge.sanbot.App;
import com.thorbridge.sanbot.BuildConfig;
import com.thorbridge.sanbot.EventLog;
import com.thorbridge.sanbot.Prefs;
import com.thorbridge.sanbot.media.AndroidCameraHub;
import com.thorbridge.sanbot.media.HdCameraHub;
import com.thorbridge.sanbot.media.MicStreamer;
import com.thorbridge.sanbot.robot.CommandDispatcher;
import com.thorbridge.sanbot.robot.RobotState;
import com.thorbridge.sanbot.robot.SanbotRobot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.thorbridge.sanbot.robot.RobotState.obj;

/**
 * All networking between the tablet and Thor:
 *  - control channel (JSON lines) as TCP server (Thor connects) and/or TCP client (tablet connects to Thor)
 *  - HD camera H.264 stream, microphone PCM stream, speaker PCM input, HTTP (MJPEG + state), UDP discovery.
 */
public class Bridge implements ControlSession.Listener, RobotState.EventSink {

    private static final String TAG = "bridge";

    private interface SocketHandler {
        void handle(Socket s) throws Exception;
    }

    private static final class H264Client {
        final QueuedSocketWriter w;
        volatile boolean needKey = true;

        H264Client(QueuedSocketWriter w) {
            this.w = w;
        }
    }

    private final App app;
    private final CopyOnWriteArrayList<ControlSession> sessions = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<H264Client> h264Clients = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<QueuedSocketWriter> micClients = new CopyOnWriteArrayList<>();
    private final List<ServerSocket> servers = new ArrayList<>();
    private final HttpServer http;
    private final Discovery discovery;

    private volatile boolean running;
    private volatile String serverStatus = "stopped";
    private volatile String clientStatus = "disabled";
    private Thread clientThread;
    private HandlerThread tickThread;
    private Handler tick;

    private final HdCameraHub.FrameSink h264Sink = (data, w, h) -> {
        boolean key = HdCameraHub.isKeyFrame(data);
        for (H264Client c : h264Clients) {
            if (c.needKey && !key) continue;
            c.needKey = false;
            if (!c.w.offer(data)) c.needKey = true;
        }
    };

    private final MicStreamer.PcmSink micSink = chunk -> {
        for (QueuedSocketWriter w : micClients) w.offer(chunk);
    };

    public Bridge(App app) {
        this.app = app;
        this.http = new HttpServer(app, this);
        this.discovery = new Discovery(app);
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void start() {
        if (running) return;
        running = true;
        Prefs p = app.prefs();
        app.state().addSink(this);

        if (p.serverEnabled()) {
            listen(p.serverPort(), "control", this::acceptControl);
            serverStatus = "listening on port " + p.serverPort();
        } else {
            serverStatus = "disabled";
        }
        listen(Prefs.PORT_H264, "h264", this::acceptH264);
        listen(Prefs.PORT_MIC, "mic", this::acceptMic);
        listen(Prefs.PORT_SPEAKER, "speaker", this::acceptSpeaker);
        http.start(Prefs.PORT_HTTP);
        discovery.start();

        if (p.clientEnabled()) {
            clientThread = new Thread(this::clientLoop, "ctrl-client");
            clientThread.start();
        } else {
            clientStatus = "disabled";
        }

        tickThread = new HandlerThread("bridge-tick");
        tickThread.start();
        tick = new Handler(tickThread.getLooper());
        tick.post(ticker);
        EventLog.i(TAG, "bridge started (server " + serverStatus + ", client " + (p.clientEnabled() ? p.thorHost() + ":" + p.thorPort() : "off") + ")");
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        app.state().removeSink(this);
        for (ServerSocket s : servers) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        servers.clear();
        for (ControlSession s : sessions) s.close();
        sessions.clear();
        for (H264Client c : h264Clients) c.w.close();
        for (QueuedSocketWriter w : micClients) w.close();
        http.stop();
        discovery.stop();
        if (clientThread != null) clientThread.interrupt();
        if (tickThread != null) tickThread.quitSafely();
        serverStatus = "stopped";
        clientStatus = "stopped";
        EventLog.i(TAG, "bridge stopped");
    }

    public void restart() {
        stop();
        start();
    }

    // ------------------------------------------------------------------ status for the UI

    public List<ControlSession> sessions() { return new ArrayList<>(sessions); }
    public String serverStatus() { return serverStatus; }
    public String clientStatus() { return clientStatus; }
    public int h264ClientCount() { return h264Clients.size(); }
    public int micClientCount() { return micClients.size(); }
    public int mjpegClientCount() { return http.mjpegClients(); }
    public Discovery discovery() { return discovery; }

    public boolean thorConnected() {
        for (ControlSession s : sessions) if (s.isOpen() && s.authenticated) return true;
        return false;
    }

    // ------------------------------------------------------------------ security

    public boolean authRequired() {
        return !app.prefs().token().isEmpty();
    }

    boolean checkToken(String t) {
        String expected = app.prefs().token();
        if (expected.isEmpty()) return true;
        if (t == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), t.getBytes(StandardCharsets.UTF_8));
    }

    /** Media ports accept any host when no token is set; otherwise only hosts with an authenticated control session. */
    boolean isIpAllowed(String ip) {
        if (!authRequired()) return true;
        for (ControlSession s : sessions) if (s.isOpen() && s.authenticated && s.remoteIp().equals(ip)) return true;
        return false;
    }

    // ------------------------------------------------------------------ servers

    private void listen(final int port, final String name, final SocketHandler h) {
        try {
            final ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
            servers.add(ss);
            Thread t = new Thread(() -> {
                while (running && !ss.isClosed()) {
                    try {
                        final Socket s = ss.accept();
                        new Thread(() -> {
                            try {
                                h.handle(s);
                            } catch (Exception e) {
                                EventLog.e(TAG, name + " connection error", e);
                                try {
                                    s.close();
                                } catch (IOException ignored) {
                                }
                            }
                        }, name + "-conn").start();
                    } catch (IOException e) {
                        if (running) EventLog.e(TAG, name + " accept failed", e);
                    }
                }
            }, name + "-accept");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            EventLog.e(TAG, "cannot listen on port " + port + " (" + name + ")", e);
            if ("control".equals(name)) serverStatus = "ERROR: port " + port + " busy";
        }
    }

    private void acceptControl(Socket s) throws IOException {
        ControlSession cs = new ControlSession(s, "server", app.prefs().stateRateHz(), !authRequired(), this);
        sessions.add(cs);
        EventLog.i(TAG, "Thor connected (server mode) from " + cs.remote());
        cs.send(helloInfo());
    }

    private void acceptH264(Socket s) throws IOException {
        String ip = s.getInetAddress().getHostAddress();
        if (!isIpAllowed(ip)) {
            EventLog.w(TAG, "rejected H.264 client " + ip + " (authenticate on the control port first)");
            s.close();
            return;
        }
        s.setTcpNoDelay(true);
        H264Client c = new H264Client(new QueuedSocketWriter(s, 90, "h264-tx", w -> {
            for (H264Client x : h264Clients) if (x.w == w) h264Clients.remove(x);
            if (h264Clients.isEmpty()) app.hdCamera().removeSink(h264Sink);
            EventLog.i(TAG, "H.264 client disconnected: " + w.remote());
        }));
        byte[] cfg = app.hdCamera().configFrame();
        if (cfg != null) c.w.offer(cfg);
        h264Clients.add(c);
        app.hdCamera().addSink(h264Sink);
        EventLog.i(TAG, "H.264 client connected: " + ip);
    }

    private void acceptMic(Socket s) throws IOException {
        String ip = s.getInetAddress().getHostAddress();
        if (!isIpAllowed(ip)) {
            s.close();
            return;
        }
        s.setTcpNoDelay(true);
        QueuedSocketWriter w = new QueuedSocketWriter(s, 250, "mic-tx", x -> {
            micClients.remove(x);
            if (micClients.isEmpty()) app.mic().removeSink(micSink);
            EventLog.i(TAG, "mic client disconnected: " + x.remote());
        });
        micClients.add(w);
        app.mic().addSink(micSink);
        EventLog.i(TAG, "mic client connected: " + ip);
    }

    private void acceptSpeaker(Socket s) throws IOException {
        String ip = s.getInetAddress().getHostAddress();
        if (!isIpAllowed(ip)) {
            s.close();
            return;
        }
        try {
            app.speaker().play(s.getInputStream(), ip);
        } finally {
            s.close();
        }
    }

    // ------------------------------------------------------------------ client mode

    private void clientLoop() {
        int backoff = 1000;
        while (running && app.prefs().clientEnabled()) {
            String host = app.prefs().thorHost().trim();
            int port = app.prefs().thorPort();
            if (host.isEmpty()) {
                clientStatus = "no Thor IP set";
                if (!sleep(2000)) return;
                continue;
            }
            ControlSession cs = null;
            try {
                clientStatus = "connecting to " + host + ":" + port + " ...";
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, port), 3000);
                cs = new ControlSession(s, "client", app.prefs().stateRateHz(), !authRequired(), this);
                sessions.add(cs);
                cs.send(helloInfo());
                clientStatus = "connected to " + host + ":" + port;
                EventLog.i(TAG, "connected to Thor at " + host + ":" + port);
                backoff = 1000;
                while (running && cs.isOpen()) {
                    if (!sleep(200)) break;
                }
                if (running) clientStatus = "link to " + host + ":" + port + " lost, reconnecting";
            } catch (IOException e) {
                clientStatus = "cannot reach " + host + ":" + port + " (" + e.getMessage() + "), retrying";
            } finally {
                if (cs != null) cs.close();
            }
            if (!sleep(backoff)) return;
            backoff = Math.min(backoff * 2, 5000);
        }
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ control messages

    @Override
    public void onMessage(ControlSession s, JSONObject msg) {
        String type = msg.optString("type", msg.has("cmd") ? "cmd" : "");
        switch (type) {
            case "auth":
                if (checkToken(msg.optString("token", null))) {
                    s.authenticated = true;
                    s.send(obj("type", "auth_ok"));
                    EventLog.i(TAG, "Thor authenticated: " + s.remote());
                } else {
                    s.send(obj("type", "error", "error", "bad token"));
                    EventLog.w(TAG, "bad token from " + s.remote());
                    s.close();
                }
                break;
            case "ping":
                s.send(obj("type", "pong", "t", System.currentTimeMillis(), "echo", msg.opt("t")));
                break;
            case "pong":
                break;
            case "config":
                if (msg.has("state_rate_hz")) s.stateRateHz = Math.max(0, Math.min(50, msg.optInt("state_rate_hz")));
                s.send(obj("type", "config_ok", "state_rate_hz", s.stateRateHz));
                break;
            case "cmd":
                if (!s.authenticated) {
                    s.send(obj("type", "ack", "id", msg.opt("id"), "cmd", msg.optString("cmd"), "ok", false,
                            "error", "auth required: send {\"type\":\"auth\",\"token\":\"...\"}"));
                    break;
                }
                s.send(app.dispatcher().execute(msg, "thor@" + s.remoteIp()));
                break;
            default:
                s.send(obj("type", "error", "error", "unknown message type '" + type + "'"));
        }
    }

    @Override
    public void onClosed(ControlSession s) {
        sessions.remove(s);
        EventLog.i(TAG, "Thor session closed: " + s.remote() + " (" + s.mode + ")");
        if (!thorConnected()) app.dispatcher().onRemoteLinkLost();
    }

    @Override
    public void onRobotEvent(String name, JSONObject data) {
        if (sessions.isEmpty()) return;
        JSONObject m = obj("type", "event", "name", name, "t", System.currentTimeMillis(), "data", data);
        for (ControlSession s : sessions) if (s.authenticated) s.send(m);
    }

    private final Runnable ticker = new Runnable() {
        private long lastStatusAt;

        @Override
        public void run() {
            long now = System.currentTimeMillis();
            JSONObject state = null;
            for (ControlSession s : sessions) {
                if (!s.authenticated || s.stateRateHz <= 0) continue;
                if (now - s.lastStateSentAt < 1000 / s.stateRateHz) continue;
                if (state == null) state = obj("type", "state", "t", now, "state", app.state().toJson());
                s.lastStateSentAt = now;
                s.send(state);
            }
            if (now - lastStatusAt > 1000) {
                lastStatusAt = now;
                StringBuilder sb = new StringBuilder();
                for (ControlSession s : sessions) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(s.remoteIp()).append(" (").append(s.mode).append(s.authenticated ? ")" : ", not authenticated)");
                }
                app.state().set(RobotState.G_ROBOT, "thor_link", "Thor link", sb.length() == 0 ? "not connected" : sb.toString());
            }
            if (running) tick.postDelayed(this, 20);
        }
    };

    // ------------------------------------------------------------------ hello

    public JSONObject helloInfo() {
        Prefs p = app.prefs();
        JSONArray ips = new JSONArray();
        for (NetUtils.Iface i : NetUtils.interfaces()) ips.put(obj("iface", i.name, "kind", i.kind(), "ip", i.ip));
        JSONArray cams = new JSONArray();
        for (AndroidCameraHub.Info c : app.cameras().list()) {
            cams.put(obj("id", c.id, "facing", c.facing, "mjpeg", "/camera/" + c.id + ".mjpg", "jpeg", "/camera/" + c.id + ".jpg"));
        }
        SanbotRobot r = app.robot();
        JSONArray cmds = new JSONArray();
        for (String c : CommandDispatcher.COMMANDS) cmds.put(c);
        return obj("type", "hello",
                "app", "sanbot-thor-bridge",
                "version", BuildConfig.VERSION_NAME,
                "robot_name", p.robotName(),
                "android", Build.VERSION.RELEASE,
                "model", Build.MODEL,
                "hardware", Build.HARDWARE,
                "manufacturer", Build.MANUFACTURER,
                "sdk_connected", r != null && r.isServiceConnected(),
                "auth_required", authRequired(),
                "remote_control_allowed", p.allowRemoteControl(),
                "ips", ips,
                "ports", obj("control", p.serverPort(), "h264", Prefs.PORT_H264, "mic", Prefs.PORT_MIC,
                        "speaker", Prefs.PORT_SPEAKER, "http", Prefs.PORT_HTTP, "discovery", Prefs.PORT_DISCOVERY),
                "hd_camera", obj("port", Prefs.PORT_H264, "codec", "h264-annexb", "snapshot", "/hd.jpg"),
                "mic", obj("port", Prefs.PORT_MIC, "format", "s16le", "rate", MicStreamer.SAMPLE_RATE, "channels", 1),
                "speaker", obj("port", Prefs.PORT_SPEAKER, "format", "s16le", "rate", 16000, "channels", 1),
                "android_cameras", cams,
                "drive_timeout_ms", p.driveTimeoutMs(),
                "commands", cmds);
    }
}
