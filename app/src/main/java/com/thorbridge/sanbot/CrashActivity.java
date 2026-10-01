package com.thorbridge.sanbot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.thorbridge.sanbot.ui.Ui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** Shows the stack trace of the last crash on the robot screen (no adb needed). Plain Activity, no Sanbot SDK. */
public class CrashActivity extends Activity {

    private static final String FILE = "last_crash.txt";

    /** True in the ":crash" process, where the bridge, SDK and cameras must not start. */
    static boolean isCrashProcess() {
        try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
            byte[] b = new byte[256];
            int n = in.read(b);
            return n > 0 && new String(b, 0, n, StandardCharsets.UTF_8).trim().endsWith(":crash");
        } catch (Exception e) {
            return false;
        }
    }

    static void install(final Context app) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Log.e("ThorBridge/crash", "uncaught exception in thread " + t.getName(), e);
            try {
                File f = new File(app.getFilesDir(), FILE);
                // A second crash within a few seconds means the crash screen itself cannot start: give up.
                boolean loop = f.exists() && System.currentTimeMillis() - f.lastModified() < 5000;
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println(new Date() + "   thread: " + t.getName());
                pw.println(Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                        + ", app " + BuildConfig.VERSION_NAME);
                pw.println();
                e.printStackTrace(pw);
                pw.println();
                pw.println("--- app log before the crash ---");
                for (String l : EventLog.snapshot()) pw.println(l);
                pw.flush();
                String text = sw.toString();
                write(f, text);
                File ext = app.getExternalFilesDir(null);
                if (ext != null) write(new File(ext, FILE), text);
                if (!loop) {
                    app.startActivity(new Intent(app, CrashActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                }
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private static void write(File f, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String read() {
        File f = new File(getFilesDir(), FILE);
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "No crash report found (" + e + ")";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(Ui.text(this, "The app crashed", 22, Ui.BAD));
        File ext = getExternalFilesDir(null);
        root.addView(Ui.text(this, "Report saved to " + (ext != null ? new File(ext, FILE).getPath() : "app storage")
                + ". Send a photo of this screen or that file.", 14, Ui.DIM));

        LinearLayout buttons = Ui.hbox(this);
        buttons.addView(Ui.button(this, "Restart app", v -> {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        }));
        buttons.addView(Ui.button(this, "Close", v -> finish()));
        root.addView(buttons);

        TextView trace = Ui.mono(this, read(), 12);
        trace.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(trace);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }
}
