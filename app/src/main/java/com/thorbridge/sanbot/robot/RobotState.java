package com.thorbridge.sanbot.robot;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe store of every sensor reading and motor state. The UI pages render it,
 * and the bridge sends it to Thor as periodic "state" messages.
 */
public class RobotState {

    public interface EventSink {
        void onRobotEvent(String name, JSONObject data);
    }

    public static final class Group {
        public final String id;
        public final String title;
        public final boolean motor;

        Group(String id, String title, boolean motor) {
            this.id = id;
            this.title = title;
            this.motor = motor;
        }
    }

    public static final class Entry {
        public final String group;
        public final String key;
        public final String label;
        public volatile Object raw;
        public volatile long updatedAt;
        public volatile long count;

        Entry(String group, String key, String label) {
            this.group = group;
            this.key = key;
            this.label = label;
        }

        public String text() {
            Object r = raw;
            if (r == null) return "no data yet";
            if (r instanceof Float || r instanceof Double) return String.format(java.util.Locale.US, "%.2f", ((Number) r).doubleValue());
            return String.valueOf(r);
        }
    }

    // Sensor / module groups
    public static final String G_ROBOT = "robot";
    public static final String G_POWER = "power";
    public static final String G_TOUCH = "touch";
    public static final String G_PIR = "pir";
    public static final String G_IR = "ir";
    public static final String G_ULTRASONIC = "ultrasonic";
    public static final String G_IMU = "imu";
    public static final String G_OBSTACLE = "obstacle";
    public static final String G_AUDIO = "audio";
    public static final String G_SPEECH = "speech";
    public static final String G_CAMERA = "camera";
    public static final String G_FACE = "face";
    public static final String G_MISC = "misc";
    // Motor / actuator groups
    public static final String G_HEAD = "head";
    public static final String G_ARMS = "arms";
    public static final String G_WHEELS = "wheels";
    public static final String G_LOCKS = "locks";
    public static final String G_ACTUATORS = "actuators";

    public static final Group[] GROUPS = {
            new Group(G_ROBOT, "Robot / SDK", false),
            new Group(G_POWER, "Battery & charging", false),
            new Group(G_TOUCH, "Touch sensors", false),
            new Group(G_PIR, "PIR motion sensors", false),
            new Group(G_IR, "Infrared distance sensors", false),
            new Group(G_ULTRASONIC, "Ultrasonic sensors", false),
            new Group(G_IMU, "Gyroscope / gravity", false),
            new Group(G_OBSTACLE, "Obstacle detection", false),
            new Group(G_AUDIO, "Microphones & speaker", false),
            new Group(G_SPEECH, "Speech (Sanbot ASR / TTS)", false),
            new Group(G_CAMERA, "Cameras", false),
            new Group(G_FACE, "Face recognition", false),
            new Group(G_MISC, "Buttons, alarms, MCU", false),
            new Group(G_HEAD, "Head (pan / tilt motors)", true),
            new Group(G_ARMS, "Arms (wing motors)", true),
            new Group(G_WHEELS, "Wheels (omni base)", true),
            new Group(G_LOCKS, "Motor lock / protection", true),
            new Group(G_ACTUATORS, "LEDs, light, projector, face", true),
    };

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<EventSink> sinks = new CopyOnWriteArrayList<>();

    public RobotState() {
        set(G_ROBOT, "sdk", "Sanbot SDK", "waiting for MainActivity");
    }

    private static String id(String group, String key) {
        return group + "/" + key;
    }

    /** Pre-register an entry so it is visible on the UI before any data arrives. */
    public synchronized Entry define(String group, String key, String label) {
        Entry e = entries.get(id(group, key));
        if (e == null) {
            e = new Entry(group, key, label);
            entries.put(id(group, key), e);
        }
        return e;
    }

    public void set(String group, String key, Object value) {
        set(group, key, key, value);
    }

    public void set(String group, String key, String label, Object value) {
        Entry e = define(group, key, label);
        e.raw = value;
        e.updatedAt = System.currentTimeMillis();
        e.count++;
    }

    public synchronized Object get(String group, String key) {
        Entry e = entries.get(id(group, key));
        return e == null ? null : e.raw;
    }

    public synchronized List<Entry> entries(String group) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) if (e.group.equals(group)) out.add(e);
        return out;
    }

    public void addSink(EventSink s) { sinks.add(s); }
    public void removeSink(EventSink s) { sinks.remove(s); }

    /** Discrete events (touch, speech result, PIR...) are pushed to Thor immediately. */
    public void event(String name, JSONObject data) {
        for (EventSink s : sinks) {
            try {
                s.onRobotEvent(name, data);
            } catch (Exception ignored) {
            }
        }
    }

    public static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put(String.valueOf(kv[i]), kv[i + 1]);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** {"group": {"key": value, ...}, ...} plus "_age_ms" per group for staleness checks. */
    public synchronized JSONObject toJson() {
        JSONObject root = new JSONObject();
        long now = System.currentTimeMillis();
        try {
            for (Entry e : entries.values()) {
                if (e.raw == null) continue;
                JSONObject g = root.optJSONObject(e.group);
                if (g == null) {
                    g = new JSONObject();
                    root.put(e.group, g);
                }
                Object v = e.raw;
                if (!(v instanceof Number || v instanceof Boolean || v instanceof String || v instanceof JSONObject)) {
                    v = String.valueOf(v);
                }
                g.put(e.key, v);
                JSONObject ages = g.optJSONObject("_age_ms");
                if (ages == null) {
                    ages = new JSONObject();
                    g.put("_age_ms", ages);
                }
                ages.put(e.key, now - e.updatedAt);
            }
        } catch (JSONException ignored) {
        }
        return root;
    }
}
