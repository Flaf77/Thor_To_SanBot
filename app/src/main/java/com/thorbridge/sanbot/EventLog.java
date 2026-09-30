package com.thorbridge.sanbot;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Small in-memory log shown on the Connection page. */
public final class EventLog {

    private static final int MAX = 400;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static int version;

    private EventLog() {}

    public static void i(String tag, String msg) {
        Log.i("ThorBridge/" + tag, msg);
        add(tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w("ThorBridge/" + tag, msg);
        add(tag, "WARN " + msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e("ThorBridge/" + tag, msg, t);
        add(tag, "ERROR " + msg + (t != null ? ": " + t : ""));
    }

    private static synchronized void add(String tag, String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        lines.addLast(ts + " [" + tag + "] " + msg);
        while (lines.size() > MAX) lines.removeFirst();
        version++;
    }

    public static synchronized int version() {
        return version;
    }

    public static synchronized List<String> snapshot() {
        return new ArrayList<>(lines);
    }
}
