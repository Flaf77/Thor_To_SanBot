package com.thorbridge.sanbot.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.thorbridge.sanbot.EventLog;
import com.thorbridge.sanbot.MainActivity;
import com.thorbridge.sanbot.Prefs;
import com.thorbridge.sanbot.net.ControlSession;
import com.thorbridge.sanbot.net.Discovery;
import com.thorbridge.sanbot.net.NetUtils;
import com.thorbridge.sanbot.robot.RobotState;

import java.util.List;

/** Connect the tablet to Thor over Ethernet / USB tethering / Wi-Fi LAN. */
public class ConnectionPage extends Page {

    private TextView ifaces, sessions, clientStatus, serverStatus, logView, pinState;
    private LinearLayout peersBox;
    private EditText host, port, serverPort, token, rate, driveTimeout, robotName;
    private CheckBox clientOn, serverOn, autostart;
    private String peersKey = "";
    private int logVersion = -1;
    private GroupView robotInfo;

    public ConnectionPage(MainActivity act) {
        super(act);
    }

    @Override
    protected View build(Context c) {
        Prefs p = app.prefs();
        ScrollView sv = new ScrollView(c);
        LinearLayout root = Ui.vbox(c);
        sv.addView(root);
        LinearLayout cols = Ui.hbox(c);
        cols.setGravity(android.view.Gravity.TOP);
        LinearLayout left = Ui.vbox(c), right = Ui.vbox(c);
        cols.addView(left, Ui.weight(1));
        cols.addView(right, Ui.weight(1));
        root.addView(cols);

        // --- this tablet
        LinearLayout me = Ui.card(c, "This tablet");
        ifaces = Ui.mono(c, "", 14);
        me.addView(ifaces);
        LinearLayout netBtns = Ui.hbox(c);
        netBtns.addView(Ui.button(c, "Network settings", v -> openSettings(Settings.ACTION_WIRELESS_SETTINGS)));
        netBtns.addView(Ui.button(c, "Wi-Fi settings", v -> openSettings(Settings.ACTION_WIFI_SETTINGS)));
        me.addView(netBtns);
        me.addView(Ui.text(c, "eth0 = USB-Ethernet adapter, rndis0 = USB tethering, wlan0 = Wi-Fi.", 12, Ui.DIM));
        left.addView(me);

        robotInfo = new GroupView(c, RobotState.G_ROBOT, "Robot / SDK");
        left.addView(robotInfo.card);

        // --- exclusive control
        LinearLayout ex = Ui.card(c, "Exclusive control (block other robot apps)");
        CheckBox block = Ui.check(c, "Stop other robot apps every 30 s while this app runs", p.blockOtherApps());
        block.setOnCheckedChangeListener((b, on) -> {
            p.setBlockOtherApps(on);
            if (on) act.runGuard();
        });
        ex.addView(block);
        CheckBox face = Ui.check(c, "Allow the Sanbot face/camera service (com.hfisone). Uncheck if the tablet camera "
                + "shows no image: it may hold the camera sensor. Needs an app restart.", p.allowFaceService());
        face.setOnCheckedChangeListener((b, on) -> {
            p.setAllowFaceService(on);
            Toast.makeText(act, "Close and reopen the app to apply", Toast.LENGTH_LONG).show();
        });
        ex.addView(face);
        LinearLayout exBtns = Ui.hbox(c);
        exBtns.addView(Ui.button(c, "Stop them now", v -> act.runGuard()));
        exBtns.addView(Ui.button(c, "Pin this app", v -> act.setPinned(true)));
        exBtns.addView(Ui.button(c, "Unpin", v -> act.setPinned(false)));
        ex.addView(exBtns);
        pinState = Ui.text(c, "", 13, Ui.TEXT);
        ex.addView(pinState);
        ex.addView(Ui.text(c, "Stops every robot app (sunbo/qihan/sanbot/hfisone/uvc) and user apps with running services, "
                + "except com.sunbo.main (MainService, required for all robot control), apps serving on 127.0.0.1 "
                + "(local services such as the camera stream), TTS engines and the keyboard. "
                + "Android lets a normal app stop only background processes: system-protected apps stay running and are "
                + "listed under 'Robot / SDK'. The face/camera service has its own switch above. "
                + "Pinning keeps other apps from coming to the front; use 'Unpin' here to leave.", 12, Ui.DIM));
        left.addView(ex);

        // --- tablet -> Thor
        LinearLayout cl = Ui.card(c, "Connect to Thor (tablet is the client)");
        clientOn = Ui.check(c, "Enabled - keep connecting to Thor, reconnect automatically", p.clientEnabled());
        cl.addView(clientOn);
        LinearLayout hp = Ui.hbox(c);
        host = Ui.edit(c, "Thor IP, e.g. 192.168.50.1", p.thorHost(), false);
        port = Ui.edit(c, "port", String.valueOf(p.thorPort()), true);
        hp.addView(Ui.text(c, "Thor IP ", 14, Ui.TEXT));
        hp.addView(host, Ui.weight(3));
        hp.addView(Ui.text(c, " port ", 14, Ui.TEXT));
        hp.addView(port, Ui.weight(1));
        cl.addView(hp);
        LinearLayout cb = Ui.hbox(c);
        cb.addView(Ui.coloredButton(c, "Connect", Ui.OK, v -> {
            clientOn.setChecked(true);
            apply();
        }));
        cb.addView(Ui.button(c, "Disconnect", v -> {
            clientOn.setChecked(false);
            apply();
        }));
        cl.addView(cb);
        clientStatus = Ui.text(c, "", 14, Ui.TEXT);
        cl.addView(clientStatus);
        cl.addView(Ui.text(c, "Thor bridges found on the network (tap to use):", 13, Ui.DIM));
        peersBox = Ui.vbox(c);
        cl.addView(peersBox);
        right.addView(cl);

        // --- Thor -> tablet
        LinearLayout sl = Ui.card(c, "Let Thor connect (tablet is the server)");
        serverOn = Ui.check(c, "Enabled - listen for Thor on this port", p.serverEnabled());
        sl.addView(serverOn);
        serverPort = Ui.edit(c, "port", String.valueOf(p.serverPort()), true);
        sl.addView(serverPort);
        serverStatus = Ui.text(c, "", 14, Ui.TEXT);
        sl.addView(serverStatus);
        right.addView(sl);

        // --- options
        LinearLayout op = Ui.card(c, "Security & options");
        token = Ui.edit(c, "Shared token (empty = no authentication)", p.token(), false);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        op.addView(labeled(c, "Token", token));
        rate = Ui.edit(c, "Hz", String.valueOf(p.stateRateHz()), true);
        op.addView(labeled(c, "State messages per second", rate));
        driveTimeout = Ui.edit(c, "ms", String.valueOf(p.driveTimeoutMs()), true);
        op.addView(labeled(c, "Drive watchdog default (ms)", driveTimeout));
        robotName = Ui.edit(c, "name", p.robotName(), false);
        op.addView(labeled(c, "Robot name", robotName));
        autostart = Ui.check(c, "Start this app when the robot boots", p.autoStartOnBoot());
        op.addView(autostart);
        op.addView(Ui.coloredButton(c, "Apply & restart bridge", Ui.ACCENT, v -> apply()));
        right.addView(op);

        // --- sessions & log
        LinearLayout ss = Ui.card(c, "Active links");
        sessions = Ui.mono(c, "", 13);
        ss.addView(sessions);
        left.addView(ss);

        LinearLayout lg = Ui.card(c, "Log");
        logView = Ui.mono(c, "", 11);
        lg.addView(logView);
        root.addView(lg);
        return sv;
    }

    private static LinearLayout labeled(Context c, String label, EditText e) {
        LinearLayout r = Ui.hbox(c);
        r.addView(Ui.text(c, label, 14, Ui.TEXT), Ui.weight(1));
        r.addView(e, Ui.weight(1));
        return r;
    }

    private void openSettings(String action) {
        try {
            act.startActivity(new Intent(action));
        } catch (Exception e) {
            Toast.makeText(act, "Cannot open settings: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void apply() {
        Prefs p = app.prefs();
        String h = host.getText().toString().trim();
        if (!h.isEmpty() && !h.matches("[A-Za-z0-9.\\-:]+")) {
            Toast.makeText(act, "Invalid Thor address", Toast.LENGTH_LONG).show();
            return;
        }
        p.setClient(clientOn.isChecked(), h, clamp(Ui.parseInt(port, 9100), 1, 65535));
        p.setServer(serverOn.isChecked(), clamp(Ui.parseInt(serverPort, 9100), 1024, 65535));
        p.setToken(token.getText().toString().trim());
        p.setStateRateHz(clamp(Ui.parseInt(rate, 5), 0, 50));
        p.setDriveTimeoutMs(clamp(Ui.parseInt(driveTimeout, 600), 100, 5000));
        String name = robotName.getText().toString().trim();
        p.setRobotName(name.isEmpty() ? "sanbot" : name);
        p.setAutoStartOnBoot(autostart.isChecked());
        EventLog.i("ui", "settings applied, restarting bridge");
        new Thread(() -> app.bridge().restart(), "bridge-restart").start();
        Toast.makeText(act, "Saved. Bridge restarting...", Toast.LENGTH_SHORT).show();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public void refresh() {
        Prefs p = app.prefs();
        StringBuilder sb = new StringBuilder();
        List<NetUtils.Iface> list = NetUtils.interfaces();
        if (list.isEmpty()) sb.append("No network! Connect Ethernet adapter, USB tethering or Wi-Fi.\n");
        for (NetUtils.Iface i : list) {
            sb.append(String.format("%-8s %-14s %s%n", i.name, i.kind(), i.ip));
        }
        sb.append("\nPorts: control ").append(p.serverPort()).append("  h264 ").append(Prefs.PORT_H264)
                .append("  mic ").append(Prefs.PORT_MIC).append("  speaker ").append(Prefs.PORT_SPEAKER)
                .append("\n       http ").append(Prefs.PORT_HTTP).append("  discovery udp ").append(Prefs.PORT_DISCOVERY)
                .append("\nAndroid ").append(Build.VERSION.RELEASE).append(" | ").append(Build.MANUFACTURER)
                .append(" ").append(Build.MODEL).append(" | ").append(Build.HARDWARE);
        ifaces.setText(sb.toString());

        clientStatus.setText("Status: " + app.bridge().clientStatus());
        serverStatus.setText("Status: " + app.bridge().serverStatus());
        robotInfo.refresh();
        boolean pinned = act.isPinned();
        pinState.setText(pinned ? "App is PINNED: other apps cannot open" : "Not pinned");
        pinState.setTextColor(pinned ? Ui.OK : Ui.DIM);

        StringBuilder s = new StringBuilder();
        for (ControlSession cs : app.bridge().sessions()) {
            s.append(cs.mode.equals("server") ? "Thor -> tablet  " : "tablet -> Thor  ").append(cs.remote())
                    .append(cs.authenticated ? "  auth OK" : "  NOT authenticated")
                    .append("  in ").append(cs.msgsIn).append(" / out ").append(cs.msgsOut)
                    .append("  ").append((System.currentTimeMillis() - cs.since) / 1000).append(" s\n");
        }
        if (s.length() == 0) s.append("No control link.\n");
        s.append("H.264 clients: ").append(app.bridge().h264ClientCount())
                .append("   mic clients: ").append(app.bridge().micClientCount())
                .append("   MJPEG clients: ").append(app.bridge().mjpegClientCount());
        sessions.setText(s.toString());

        List<Discovery.Peer> peers = app.bridge().discovery().peers();
        StringBuilder key = new StringBuilder();
        for (Discovery.Peer pe : peers) key.append(pe.ip).append(pe.port).append(pe.name);
        if (!key.toString().equals(peersKey)) {
            peersKey = key.toString();
            peersBox.removeAllViews();
            if (peers.isEmpty()) {
                peersBox.addView(Ui.text(act, "none yet (Thor must run the bridge script, which sends a UDP beacon)", 13, Ui.DIM));
            }
            for (final Discovery.Peer pe : peers) {
                peersBox.addView(Ui.button(act, pe.name + "  " + pe.ip + ":" + pe.port, v -> {
                    host.setText(pe.ip);
                    port.setText(String.valueOf(pe.port));
                }));
            }
        }

        int v = com.thorbridge.sanbot.EventLog.version();
        if (v != logVersion) {
            logVersion = v;
            List<String> lines = EventLog.snapshot();
            StringBuilder lb = new StringBuilder();
            for (int i = Math.max(0, lines.size() - 80); i < lines.size(); i++) lb.append(lines.get(i)).append('\n');
            logView.setText(lb.toString());
        }
    }
}
