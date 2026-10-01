package com.thorbridge.sanbot;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {

    /** Fixed ports served by the tablet. The control port is configurable. */
    public static final int PORT_H264 = 9101;
    public static final int PORT_MIC = 9102;
    public static final int PORT_SPEAKER = 9103;
    public static final int PORT_HTTP = 8080;
    public static final int PORT_DISCOVERY = 9199;

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE);
    }

    public boolean serverEnabled() { return sp.getBoolean("server_enabled", true); }
    public int serverPort() { return sp.getInt("server_port", 9100); }
    public boolean clientEnabled() { return sp.getBoolean("client_enabled", false); }
    public String thorHost() { return sp.getString("thor_host", ""); }
    public int thorPort() { return sp.getInt("thor_port", 9100); }
    public String token() { return sp.getString("token", ""); }
    public boolean allowRemoteControl() { return sp.getBoolean("allow_remote", true); }
    public int stateRateHz() { return sp.getInt("state_rate_hz", 5); }
    public int driveTimeoutMs() { return sp.getInt("drive_timeout_ms", 600); }
    public boolean autoStartOnBoot() { return sp.getBoolean("autostart", true); }
    public String robotName() { return sp.getString("robot_name", "sanbot"); }
    public boolean blockOtherApps() { return sp.getBoolean("block_other_apps", true); }
    public boolean allowFaceService() { return sp.getBoolean("allow_face_service", true); }

    public void setServer(boolean enabled, int port) {
        sp.edit().putBoolean("server_enabled", enabled).putInt("server_port", port).apply();
    }

    public void setClient(boolean enabled, String host, int port) {
        sp.edit().putBoolean("client_enabled", enabled).putString("thor_host", host).putInt("thor_port", port).apply();
    }

    public void setToken(String token) { sp.edit().putString("token", token).apply(); }
    public void setAllowRemoteControl(boolean b) { sp.edit().putBoolean("allow_remote", b).apply(); }
    public void setStateRateHz(int hz) { sp.edit().putInt("state_rate_hz", hz).apply(); }
    public void setDriveTimeoutMs(int ms) { sp.edit().putInt("drive_timeout_ms", ms).apply(); }
    public void setAutoStartOnBoot(boolean b) { sp.edit().putBoolean("autostart", b).apply(); }
    public void setRobotName(String n) { sp.edit().putString("robot_name", n).apply(); }
    public void setBlockOtherApps(boolean b) { sp.edit().putBoolean("block_other_apps", b).apply(); }
    public void setAllowFaceService(boolean b) { sp.edit().putBoolean("allow_face_service", b).apply(); }
}
