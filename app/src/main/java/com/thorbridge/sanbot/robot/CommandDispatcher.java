package com.thorbridge.sanbot.robot;

import android.content.Context;
import android.media.AudioManager;

import com.thorbridge.sanbot.App;
import com.thorbridge.sanbot.EventLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static com.thorbridge.sanbot.robot.RobotState.obj;

/**
 * Single entry point for every command, from Thor (JSON over TCP) or from the tablet UI.
 * Request:  {"type":"cmd","id":7,"cmd":"head.absolute","args":{"pan":90,"tilt":15}}
 * Response: {"type":"ack","id":7,"cmd":"head.absolute","ok":true,"result":{...}}
 */
public class CommandDispatcher {

    public static final String SRC_TABLET = "tablet";

    private static final Set<String> MOTION = new HashSet<>(Arrays.asList(
            "head.absolute", "head.locate", "head.relative", "head.center",
            "arm.absolute", "arm.relative", "arm.move",
            "wheels.drive", "wheels.turn", "wheels.distance",
            "wander", "follow", "charge"));

    public static final String[] COMMANDS = {
            "ping", "help", "get_state", "get_info",
            "head.absolute {pan?,tilt?}", "head.locate {pan,tilt,lock?}", "head.relative {direction,angle}",
            "head.center", "head.stop",
            "arm.absolute {side,angle,speed?}", "arm.relative {side,direction,angle,speed?}", "arm.move {side,direction,speed?}",
            "wheels.drive {action,speed?,timeout_ms?}", "wheels.turn {direction,angle,speed?}",
            "wheels.distance {direction,cm,speed?}", "wheels.stop", "stop_all",
            "speak {text,engine?(android|sanbot),lang?(auto|en|zh),speed?,intonation?}", "speak.stop", "speech.wakeup", "speech.sleep",
            "led {part,mode,delay?,random?}", "white_light {on,level?}", "emotion {name}", "projector {on}",
            "wander {on}", "follow {on}", "charge {on}",
            "volume {percent}", "screen.text {text}",
    };

    private final App app;
    private volatile boolean estop;
    private volatile String screenText = "";
    private volatile String lastSource = "";

    public CommandDispatcher(App app) {
        this.app = app;
    }

    public boolean isEstopLatched() { return estop; }
    public String screenText() { return screenText; }
    public String lastSource() { return lastSource; }

    /** Tablet E-STOP: stops everything and rejects motion from Thor until released on the tablet. */
    public void setEstop(boolean on) {
        estop = on;
        SanbotRobot r = app.robot();
        if (on && r != null) r.stopAll("E-STOP on tablet");
        app.state().set(RobotState.G_WHEELS, "estop", "E-STOP", on ? "LATCHED" : "released");
        app.state().event("estop", obj("latched", on));
        EventLog.i("cmd", "E-STOP " + (on ? "latched" : "released"));
    }

    /** Called when a Thor control session disconnects: never leave the base driving. */
    public void onRemoteLinkLost() {
        SanbotRobot r = app.robot();
        if (r != null && r.isDriving()) r.wheelsStop("Thor link lost");
    }

    public JSONObject tablet(String cmd, Object... kv) {
        return execute(obj("type", "cmd", "cmd", cmd, "args", obj(kv)), SRC_TABLET);
    }

    public JSONObject execute(JSONObject msg, String source) {
        Object id = msg.opt("id");
        String cmd = msg.optString("cmd", "");
        JSONObject a = msg.optJSONObject("args");
        if (a == null) a = new JSONObject();
        JSONObject ack = obj("type", "ack", "cmd", cmd);
        try {
            if (id != null) ack.put("id", id);
            lastSource = source;
            if (!SRC_TABLET.equals(source) && (MOTION.contains(cmd) || cmd.startsWith("wheels.drive"))) {
                if (estop) throw new IllegalStateException("E-STOP is latched on the tablet");
                if (!app.prefs().allowRemoteControl()) throw new IllegalStateException("remote motion control is disabled on the tablet");
            }
            JSONObject result = run(cmd, a);
            ack.put("ok", result.optBoolean("ok", true));
            ack.put("result", result);
        } catch (Exception e) {
            try {
                ack.put("ok", false);
                ack.put("error", e.getMessage() != null ? e.getMessage() : e.toString());
            } catch (Exception ignored) {
            }
            if (!"wheels.drive".equals(cmd)) EventLog.w("cmd", source + " " + cmd + " -> " + e.getMessage());
        }
        return ack;
    }

    private SanbotRobot robot() {
        SanbotRobot r = app.robot();
        if (r == null) throw new IllegalStateException("Sanbot SDK not attached: open the app on the robot tablet");
        if (!r.isServiceConnected()) {
            throw new IllegalStateException("Sanbot SDK not connected to the robot MainService (see 'Robot / SDK' on the Modules page)");
        }
        return r;
    }

    private static int speed(JSONObject a) {
        return a.optInt("speed", 5);
    }

    private static String req(JSONObject a, String key) {
        String v = a.optString(key, null);
        if (v == null || v.isEmpty()) throw new IllegalArgumentException("missing arg '" + key + "'");
        return v;
    }

    private static int reqInt(JSONObject a, String key) {
        if (!a.has(key)) throw new IllegalArgumentException("missing arg '" + key + "'");
        return a.optInt(key);
    }

    private JSONObject run(String cmd, JSONObject a) throws Exception {
        switch (cmd) {
            case "ping":
                return obj("ok", true, "pong", System.currentTimeMillis());
            case "help": {
                JSONArray arr = new JSONArray();
                for (String c : COMMANDS) arr.put(c);
                return obj("ok", true, "commands", arr);
            }
            case "get_state":
                return obj("ok", true, "state", app.state().toJson());
            case "get_info":
                return obj("ok", true, "info", app.bridge().helloInfo());

            case "head.absolute":
                return robot().headAbsolute(a.has("pan") ? a.getInt("pan") : null, a.has("tilt") ? a.getInt("tilt") : null);
            case "head.locate":
                return robot().headLocate(reqInt(a, "pan"), reqInt(a, "tilt"), a.optString("lock", "none"));
            case "head.relative":
                return robot().headRelative(req(a, "direction"), reqInt(a, "angle"));
            case "head.center":
                return robot().headCenter();
            case "head.stop":
                return robot().headStop();

            case "arm.absolute":
                return robot().armAbsolute(req(a, "side"), reqInt(a, "angle"), speed(a));
            case "arm.relative":
                return robot().armRelative(req(a, "side"), req(a, "direction"), reqInt(a, "angle"), speed(a));
            case "arm.move":
                return robot().armMove(req(a, "side"), req(a, "direction"), speed(a));

            case "wheels.drive":
                return robot().drive(req(a, "action"), speed(a), a.optInt("timeout_ms", app.prefs().driveTimeoutMs()));
            case "wheels.turn":
                return robot().turn(req(a, "direction"), reqInt(a, "angle"), speed(a));
            case "wheels.distance":
                return robot().driveDistance(req(a, "direction"), reqInt(a, "cm"), speed(a));
            case "wheels.stop":
                return robot().wheelsStop("command");
            case "stop_all":
                return robot().stopAll("command");

            case "speak":
                // The Sanbot speech service on MainService 1.5.x accepts speak but stays silent, so Android TTS is the default.
                if ("sanbot".equals(a.optString("engine", "android"))) {
                    return robot().speak(req(a, "text"), a.optString("lang", "auto"), a.optInt("speed", 50), a.optInt("intonation", 50));
                }
                return app.tts().speak(req(a, "text"), a.optString("lang", "auto"), a.optInt("speed", 50), a.optInt("intonation", 50));
            case "speak.stop": {
                app.tts().stop();
                SanbotRobot r = app.robot();
                return r != null && r.isServiceConnected() ? r.stopSpeak() : obj("ok", true);
            }
            case "speech.wakeup":
                return robot().wakeUp();
            case "speech.sleep":
                return robot().sleep();

            case "led":
                return robot().led(a.optString("part", "all"), req(a, "mode"), a.optInt("delay", 1), a.optInt("random", 1));
            case "white_light":
                return robot().whiteLight(a.optBoolean("on"), a.has("level") ? a.getInt("level") : null);
            case "emotion":
                return robot().emotion(req(a, "name"));
            case "projector":
                return robot().projector(a.optBoolean("on"));
            case "wander":
                return robot().wander(a.optBoolean("on"));
            case "follow":
                return robot().follow(a.optBoolean("on"));
            case "charge":
                return robot().autoCharge(a.optBoolean("on"));

            case "volume": {
                AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int v = Math.round(SanbotRobot.clamp(reqInt(a, "percent"), 0, 100) / 100f * max);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                app.state().set(RobotState.G_AUDIO, "volume", "Speaker volume", v + "/" + max);
                return obj("ok", true, "volume", v, "max", max);
            }
            case "screen.text":
                screenText = a.optString("text", "");
                return obj("ok", true);
            default:
                throw new IllegalArgumentException("unknown command '" + cmd + "' (send {\"cmd\":\"help\"})");
        }
    }
}
