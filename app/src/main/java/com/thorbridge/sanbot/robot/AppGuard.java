package com.thorbridge.sanbot.robot;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import com.thorbridge.sanbot.App;
import com.thorbridge.sanbot.EventLog;
import com.thorbridge.sanbot.net.NetUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stops other apps that could drive the robot or grab its cameras while this app runs.
 * A normal app can only kill background processes: persistent system apps survive and are reported.
 */
@SuppressWarnings("deprecation")
public class AppGuard {

    private static final String TAG = "guard";

    // Every SDK command goes through MainService; killing it would cut the robot off.
    private static final Set<String> KEEP = new HashSet<>(java.util.Collections.singletonList("com.sunbo.main"));
    private static final String[] ROBOT_HINTS = {"sunbo", "qihan", "sanbot", "hfisone", "uvc"};
    public static final String FACE_SERVICE_PKG = "com.hfisone";

    private final Context ctx;
    private final RobotState st;

    public AppGuard(Context ctx, RobotState st) {
        this.ctx = ctx.getApplicationContext();
        this.st = st;
    }

    /** Robot apps, plus user-installed apps that hold a running service. */
    private Set<String> targets(PackageManager pm, ActivityManager am) {
        Set<String> keep = new HashSet<>(KEEP);
        keep.add(ctx.getPackageName());
        keep.addAll(ttsAndInputPackages(pm));
        if (App.get().prefs().allowFaceService()) keep.add(FACE_SERVICE_PKG);
        // Apps serving on 127.0.0.1 are local services (e.g. the HD camera stream the SDK connects to).
        for (String s : NetUtils.listeningPorts(ctx)) {
            if (s.startsWith("LISTEN 127.0.0.1:")) keep.add(s.substring(s.lastIndexOf(' ') + 1));
        }

        Set<String> running = new HashSet<>();
        for (ActivityManager.RunningServiceInfo s : am.getRunningServices(500)) running.add(s.service.getPackageName());

        Set<String> out = new TreeSet<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            String n = ai.packageName;
            if (keep.contains(n)) continue;
            boolean system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            if (isRobotPackage(n) || (!system && running.contains(n))) out.add(n);
        }
        return out;
    }

    static boolean isRobotPackage(String pkg) {
        String n = pkg.toLowerCase(Locale.US);
        for (String h : ROBOT_HINTS) if (n.contains(h)) return true;
        return false;
    }

    /** Text-to-speech engines and the keyboard must keep working. */
    private Set<String> ttsAndInputPackages(PackageManager pm) {
        Set<String> out = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentServices(new Intent("android.intent.action.TTS_SERVICE"), 0)) {
            out.add(ri.serviceInfo.packageName);
        }
        String ime = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.contains("/")) out.add(ime.substring(0, ime.indexOf('/')));
        return out;
    }

    /** Kills the background processes of every target app and reports which ones are still running. */
    public synchronized void sweep() {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            PackageManager pm = ctx.getPackageManager();
            Set<String> targets = targets(pm, am);
            for (String t : targets) am.killBackgroundProcesses(t);

            Set<String> survivors = new TreeSet<>();
            for (ActivityManager.RunningServiceInfo s : am.getRunningServices(500)) {
                String p = s.service.getPackageName();
                if (targets.contains(p)) survivors.add(p + (s.foreground ? " (foreground service)" : ""));
            }
            List<String> stopped = new ArrayList<>(targets);
            for (String s : survivors) stopped.remove(s.split(" ")[0]);
            st.set(RobotState.G_ROBOT, "guard_stopped", "Other apps stopped", stopped.isEmpty() ? "none running" : join(stopped));
            st.set(RobotState.G_ROBOT, "guard_survivors", "Other apps still running (system-protected)",
                    survivors.isEmpty() ? "none" : join(survivors));
            if (!survivors.isEmpty()) EventLog.w(TAG, "cannot stop (protected by the system): " + join(survivors));
        } catch (Exception e) {
            EventLog.e(TAG, "sweep failed", e);
        }
    }

    private static String join(Iterable<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) sb.append(sb.length() > 0 ? "\n" : "").append(s);
        return sb.toString();
    }
}
