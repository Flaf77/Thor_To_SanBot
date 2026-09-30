package com.thorbridge.sanbot.net;

import android.content.Context;
import android.net.wifi.WifiManager;

import com.thorbridge.sanbot.App;
import com.thorbridge.sanbot.EventLog;
import com.thorbridge.sanbot.Prefs;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.thorbridge.sanbot.robot.RobotState.obj;

/**
 * UDP discovery on port 9199.
 *  - The tablet broadcasts {"type":"sanbot_beacon",...} every 2 s on every interface.
 *  - Thor can broadcast {"type":"thor_beacon","name":"thor","port":9100}; those show up on the Connection page.
 *  - Thor can send {"type":"discover"} and gets a unicast beacon back.
 */
public class Discovery {

    public static final class Peer {
        public final String ip;
        public volatile String name;
        public volatile int port;
        public volatile long lastSeen;

        Peer(String ip) {
            this.ip = ip;
        }
    }

    private final App app;
    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private DatagramSocket socket;
    private WifiManager.MulticastLock lock;
    private volatile boolean running;

    Discovery(App app) {
        this.app = app;
    }

    synchronized void start() {
        try {
            WifiManager wm = (WifiManager) app.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("thor-bridge-discovery");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception e) {
            EventLog.w("discovery", "no multicast lock: " + e.getMessage());
        }
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(Prefs.PORT_DISCOVERY));
        } catch (Exception e) {
            EventLog.e("discovery", "cannot bind UDP " + Prefs.PORT_DISCOVERY, e);
            return;
        }
        running = true;
        final DatagramSocket s = socket;
        new Thread(() -> rxLoop(s), "discovery-rx").start();
        new Thread(() -> txLoop(s), "discovery-tx").start();
    }

    synchronized void stop() {
        running = false;
        if (socket != null) socket.close();
        socket = null;
        if (lock != null && lock.isHeld()) lock.release();
    }

    public List<Peer> peers() {
        synchronized (peers) {
            long now = System.currentTimeMillis();
            Iterator<Peer> it = peers.values().iterator();
            while (it.hasNext()) if (now - it.next().lastSeen > 15000) it.remove();
            return new ArrayList<>(peers.values());
        }
    }

    private byte[] beacon() {
        Prefs p = app.prefs();
        JSONObject b = obj("type", "sanbot_beacon", "name", p.robotName(), "control_port", p.serverPort(),
                "server_enabled", p.serverEnabled(), "auth_required", !p.token().isEmpty(),
                "ports", obj("h264", Prefs.PORT_H264, "mic", Prefs.PORT_MIC, "speaker", Prefs.PORT_SPEAKER, "http", Prefs.PORT_HTTP));
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void txLoop(DatagramSocket s) {
        while (running) {
            byte[] data = beacon();
            List<InetAddress> targets = new ArrayList<>();
            for (NetUtils.Iface i : NetUtils.interfaces()) if (i.broadcast != null) targets.add(i.broadcast);
            try {
                targets.add(InetAddress.getByName("255.255.255.255"));
            } catch (Exception ignored) {
            }
            for (InetAddress a : targets) {
                try {
                    s.send(new DatagramPacket(data, data.length, a, Prefs.PORT_DISCOVERY));
                } catch (Exception ignored) {
                }
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void rxLoop(DatagramSocket s) {
        byte[] buf = new byte[2048];
        while (running) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                s.receive(pkt);
                JSONObject o = new JSONObject(new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8));
                String type = o.optString("type");
                String ip = pkt.getAddress().getHostAddress();
                if ("thor_beacon".equals(type)) {
                    synchronized (peers) {
                        Peer p = peers.get(ip);
                        if (p == null) {
                            p = new Peer(ip);
                            peers.put(ip, p);
                            EventLog.i("discovery", "found Thor at " + ip);
                        }
                        p.name = o.optString("name", "thor");
                        p.port = o.optInt("port", 9100);
                        p.lastSeen = System.currentTimeMillis();
                    }
                } else if ("discover".equals(type)) {
                    byte[] data = beacon();
                    s.send(new DatagramPacket(data, data.length, pkt.getAddress(), pkt.getPort()));
                }
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }
}
