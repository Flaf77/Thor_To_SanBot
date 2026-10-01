package com.thorbridge.sanbot.robot;

import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.qihancloud.opensdk.beans.OperationResult;
import com.qihancloud.opensdk.function.beans.EmotionsType;
import com.qihancloud.opensdk.function.beans.FaceRecognizeBean;
import com.qihancloud.opensdk.function.beans.LED;
import com.qihancloud.opensdk.function.beans.SpeakOption;
import com.qihancloud.opensdk.function.beans.StreamOption;
import com.qihancloud.opensdk.function.beans.handmotion.AbsoluteAngleHandMotion;
import com.qihancloud.opensdk.function.beans.handmotion.NoAngleHandMotion;
import com.qihancloud.opensdk.function.beans.handmotion.RelativeAngleHandMotion;
import com.qihancloud.opensdk.function.beans.headmotion.AbsoluteAngleHeadMotion;
import com.qihancloud.opensdk.function.beans.headmotion.LocateAbsoluteAngleHeadMotion;
import com.qihancloud.opensdk.function.beans.headmotion.RelativeAngleHeadMotion;
import com.qihancloud.opensdk.function.beans.speech.Grammar;
import com.qihancloud.opensdk.function.beans.wheelmotion.DistanceWheelMotion;
import com.qihancloud.opensdk.function.beans.wheelmotion.NoAngleWheelMotion;
import com.qihancloud.opensdk.function.beans.wheelmotion.RelativeAngleWheelMotion;
import com.qihancloud.opensdk.function.unit.HandMotionManager;
import com.qihancloud.opensdk.function.unit.HardWareManager;
import com.qihancloud.opensdk.function.unit.HeadMotionManager;
import com.qihancloud.opensdk.function.unit.MediaManager;
import com.qihancloud.opensdk.function.unit.ModularMotionManager;
import com.qihancloud.opensdk.function.unit.ProjectorManager;
import com.qihancloud.opensdk.function.unit.SpeechManager;
import com.qihancloud.opensdk.function.unit.SystemManager;
import com.qihancloud.opensdk.function.unit.WheelMotionManager;
import com.qihancloud.opensdk.function.unit.interfaces.IDarlingListener;
import com.qihancloud.opensdk.function.unit.interfaces.hardware.GyroscopeListener;
import com.qihancloud.opensdk.function.unit.interfaces.hardware.InfrareListener;
import com.qihancloud.opensdk.function.unit.interfaces.hardware.PIRListener;
import com.qihancloud.opensdk.function.unit.interfaces.hardware.TouchSensorListener;
import com.qihancloud.opensdk.function.unit.interfaces.hardware.VoiceLocateListener;
import com.qihancloud.opensdk.function.unit.interfaces.media.FaceRecognizeListener;
import com.qihancloud.opensdk.function.unit.interfaces.media.MediaStreamListener;
import com.qihancloud.opensdk.function.unit.interfaces.speech.RecognizeListener;
import com.qihancloud.opensdk.function.unit.interfaces.speech.SpeakListener;
import com.qihancloud.opensdk.function.unit.interfaces.speech.WakenListener;
import com.thorbridge.sanbot.App;
import com.thorbridge.sanbot.EventLog;
import com.thorbridge.sanbot.net.NetUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import static com.thorbridge.sanbot.robot.RobotState.obj;

/**
 * Hardware abstraction over the Qihan/Sanbot OpenSDK 1.1.8 managers (MainService com.sunbo.main 1.5.x, Sanbot Elf).
 * Every SDK call and every SDK callback in the app goes through this class.
 */
public class SanbotRobot {

    private static final String TAG = "robot";

    // Sanbot Elf ranges (degrees). The MCU clamps as well.
    public static final int PAN_MIN = 0, PAN_MAX = 180, PAN_CENTER = 90;
    public static final int TILT_MIN = 7, TILT_MAX = 30, TILT_CENTER = 15;
    public static final int ARM_MIN = 0, ARM_MAX = 270, ARM_DOWN = 180;
    public static final int SPEED_MIN = 1, SPEED_MAX = 10;

    // Touch part ids from the Sanbot SDK docs. Verify on your unit by touching each spot.
    private static final String[] TOUCH_NAMES = {
            "?", "Chin left", "Chin right", "Chest left", "Chest right",
            "Back of head left", "Back of head right", "Back left", "Back right",
            "Left hand / arm", "Right hand / arm", "Head top (middle)", "Head top (front)", "Head top (back)"
    };

    public interface ManagerSource {
        Object get(String name);
    }

    private final RobotState st;
    private final HardWareManager hw;
    private final SystemManager sys;
    private final HeadMotionManager head;
    private final HandMotionManager hand;
    private final WheelMotionManager wheel;
    private final SpeechManager speech;
    private final MediaManager hdCam;
    private final ProjectorManager projector;
    private final ModularMotionManager modular;

    // SDK 1.1.8 does not report the HD stream size; this is the Elf head camera main stream.
    private static final int HD_W = 1280, HD_H = 720;
    private volatile String hdError;

    private final HandlerThread thread = new HandlerThread("robot-poll");
    private Handler handler;
    private volatile boolean serviceConnected;
    private long startedAt;

    // Drive watchdog state
    private volatile boolean driving;
    private volatile byte driveAction = NoAngleWheelMotion.ACTION_STOP_RUN;
    private volatile int driveSpeed;
    private volatile long driveDeadline;

    /** hdCam may be null if the camera native library could not be loaded. */
    public SanbotRobot(RobotState state, HardWareManager hw, SystemManager sys, HeadMotionManager head,
                       HandMotionManager hand, WheelMotionManager wheel, SpeechManager speech,
                       MediaManager hdCam, ProjectorManager projector, ModularMotionManager modular) {
        this.st = state;
        this.hw = hw;
        this.sys = sys;
        this.head = head;
        this.hand = hand;
        this.wheel = wheel;
        this.speech = speech;
        this.hdCam = hdCam;
        this.projector = projector;
        this.modular = modular;
        defineEntries();
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        startedAt = SystemClock.uptimeMillis();
        thread.start();
        handler = new Handler(thread.getLooper());
        registerListeners();
        handler.post(poller);
        handler.post(watchdog);
        handler.post(this::listSystemApps);
        st.set(RobotState.G_ROBOT, "sdk", "Sanbot SDK", "managers created, waiting for MainService");
    }

    public void stop() {
        try {
            wheelsStop("app closing");
        } catch (Exception ignored) {
        }
        if (handler != null) handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
        st.set(RobotState.G_ROBOT, "sdk", "Sanbot SDK", "detached (app not in foreground)");
    }

    public void onMainServiceConnected() {
        serviceConnected = true;
        st.set(RobotState.G_ROBOT, "sdk", "Sanbot SDK", "connected to MainService (SDK 1.1.8)");
        EventLog.i(TAG, "Sanbot MainService connected");
        handler.post(() -> {
            readStaticInfo();
            App.get().hdCamera().ensureOpen();
        });
    }

    public boolean isServiceConnected() {
        return serviceConnected;
    }

    private void defineEntries() {
        for (int i = 1; i < TOUCH_NAMES.length; i++) {
            st.define(RobotState.G_TOUCH, "part_" + i, "#" + i + " " + TOUCH_NAMES[i]);
        }
        st.define(RobotState.G_PIR, "front", "Front PIR");
        st.define(RobotState.G_PIR, "back", "Back PIR");
        st.define(RobotState.G_POWER, "battery_percent", "Battery %");
        st.define(RobotState.G_POWER, "battery_status", "Power source");
        st.define(RobotState.G_IMU, "gyro_x", "Gyro / heading X");
        st.define(RobotState.G_IMU, "gyro_y", "Gyro Y");
        st.define(RobotState.G_IMU, "gyro_z", "Gyro Z");
        st.define(RobotState.G_AUDIO, "voice_angle", "Sound source angle (mic array)");
        st.define(RobotState.G_SPEECH, "awake", "Wake state");
        st.define(RobotState.G_SPEECH, "last_text", "Last recognized text");
        st.define(RobotState.G_SPEECH, "speaking", "TTS status");
        st.define(RobotState.G_FACE, "count", "Faces seen");
        st.define(RobotState.G_HEAD, "pan_cmd", "Pan (horizontal) commanded");
        st.define(RobotState.G_HEAD, "tilt_cmd", "Tilt (vertical) commanded");
        st.define(RobotState.G_HEAD, "last_cmd", "Last command");
        st.define(RobotState.G_ARMS, "left_cmd", "Left arm commanded angle");
        st.define(RobotState.G_ARMS, "right_cmd", "Right arm commanded angle");
        st.define(RobotState.G_ARMS, "last_cmd", "Last command");
        st.define(RobotState.G_WHEELS, "action", "Current action");
        st.define(RobotState.G_WHEELS, "speed", "Speed");
        st.define(RobotState.G_WHEELS, "watchdog", "Drive watchdog");
        st.define(RobotState.G_WHEELS, "last_cmd", "Last command");
    }

    private void reportWaiting() {
        long waited = (SystemClock.uptimeMillis() - startedAt) / 1000;
        if (waited < 5 || serviceConnected) return;
        st.set(RobotState.G_ROBOT, "sdk", "Sanbot SDK", "NOT CONNECTED after " + waited
                + " s: com.sunbo.main did not accept the binding (is MainService running?)");
    }

    private void listSystemApps() {
        StringBuilder sb = new StringBuilder();
        try {
            String me = App.get().getPackageName();
            for (PackageInfo p : App.get().getPackageManager().getInstalledPackages(0)) {
                String n = p.packageName.toLowerCase(Locale.US);
                if (n.equals(me) || !(n.contains("sunbo") || n.contains("qihan") || n.contains("sanbot") || n.contains("hfisone"))) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(p.packageName).append("  v").append(p.versionName).append(" (").append(p.versionCode).append(')');
            }
        } catch (Exception e) {
            EventLog.e(TAG, "cannot list system apps", e);
        }
        st.set(RobotState.G_ROBOT, "system_apps", "Sanbot system apps", sb.length() == 0 ? "none found" : sb.toString());
        StringBuilder ports = new StringBuilder();
        for (String p : NetUtils.listeningPorts(App.get())) ports.append(ports.length() > 0 ? "\n" : "").append(p);
        st.set(RobotState.G_ROBOT, "local_ports", "Listening TCP ports (tablet)", ports.length() == 0 ? "none found" : ports.toString());
    }

    private void readStaticInfo() {
        safe("info", () -> {
            st.set(RobotState.G_ROBOT, "main_service_version", "MainService version", sys.getMainServiceVersion());
            st.set(RobotState.G_ROBOT, "device_id", "Robot device id", sys.getDeviceId());
        });
    }

    // ------------------------------------------------------------------ polling + watchdog

    private int pollTick;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            pollTick++;
            if (pollTick % 4 == 0) {
                safe("battery", () -> {
                    st.set(RobotState.G_POWER, "battery_percent", "Battery %", sys.getBatteryValue());
                    int s = sys.getBatteryStatus();
                    String t = s == SystemManager.STATUS_CHARGE_PILE ? "charging (dock)"
                            : s == SystemManager.STATUS_CHARGE_LINE ? "charging (cable)"
                            : s == SystemManager.STATUS_NORMAL ? "on battery" : "status " + s;
                    st.set(RobotState.G_POWER, "battery_status", "Power source", t);
                });
            }
            if (!serviceConnected && pollTick % 6 == 1) reportWaiting();
            handler.postDelayed(this, 500);
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (driving && SystemClock.uptimeMillis() > driveDeadline) {
                wheelsStop("watchdog: no drive command received in time");
                st.set(RobotState.G_WHEELS, "watchdog", "Drive watchdog", "TRIPPED (stopped wheels)");
            }
            handler.postDelayed(this, 50);
        }
    };

    // ------------------------------------------------------------------ SDK callbacks

    private void registerListeners() {
        hw.setOnHareWareListener(new TouchSensorListener() {
            @Override
            public void onTouch(int part) {
                touch(part, true);
            }
        });
        hw.setOnHareWareListener(new PIRListener() {
            @Override
            public void onPIRCheckResult(boolean triggered, int part) {
                String key = part == 1 ? "front" : part == 2 ? "back" : "part_" + part;
                st.set(RobotState.G_PIR, key, key.equals("front") ? "Front PIR" : key.equals("back") ? "Back PIR" : "PIR " + part,
                        triggered ? "MOTION" : "idle");
                st.event("pir", obj("part", key, "triggered", triggered));
            }
        });
        hw.setOnHareWareListener(new InfrareListener() {
            @Override
            public void infrareDistance(int part, int distance) {
                st.set(RobotState.G_IR, "ir_" + part, "IR #" + part + " distance", distance);
            }
        });
        hw.setOnHareWareListener(new GyroscopeListener() {
            @Override
            public void gyroscopeData(float x, float y, float z) {
                st.set(RobotState.G_IMU, "gyro_x", "Gyro / heading X", x);
                st.set(RobotState.G_IMU, "gyro_y", "Gyro Y", y);
                st.set(RobotState.G_IMU, "gyro_z", "Gyro Z", z);
            }
        });
        hw.setOnHareWareListener(new VoiceLocateListener() {
            @Override
            public void voiceLocateResult(int angle) {
                st.set(RobotState.G_AUDIO, "voice_angle", "Sound source angle (mic array)", angle);
                st.event("voice_locate", obj("angle", angle));
            }
        });
        sys.setOnIDarlingListener(new IDarlingListener() {
            @Override
            public void onAlarm(int code) {
                st.set(RobotState.G_MISC, "alarm", "Safety alarm", code);
                st.event("alarm", obj("code", code));
            }
        });

        speech.setOnSpeechListener(new RecognizeListener() {
            @Override
            public boolean onRecognizeResult(Grammar g) {
                String text = g.getText();
                st.set(RobotState.G_SPEECH, "last_text", "Last recognized text", text);
                st.event("speech", obj("text", text, "topic", g.getTopic(), "action", g.getAction(), "engine", g.getEngine()));
                // Must return quickly (<300 ms). true = the app handled it, Sanbot will not answer by itself.
                return true;
            }

            @Override
            public void onRecognizeVolume(int v) {
                st.set(RobotState.G_AUDIO, "asr_volume", "Mic volume (Sanbot ASR, 0-30)", v);
            }
        });
        speech.setOnSpeechListener(new WakenListener() {
            @Override
            public void onWakeUp() {
                st.set(RobotState.G_SPEECH, "awake", "Wake state", "awake");
                st.event("wake", obj("awake", true));
            }

            @Override
            public void onSleep() {
                st.set(RobotState.G_SPEECH, "awake", "Wake state", "sleeping");
                st.event("wake", obj("awake", false));
            }
        });
        speech.setOnSpeechListener(new SpeakListener() {
            @Override
            public void onSpeakFinish() {
                st.set(RobotState.G_SPEECH, "speaking", "TTS status", "finished");
                st.event("speak_status", obj("progress", 100, "finished", true));
            }

            @Override
            public void onSpeakProgress(int progress) {
                st.set(RobotState.G_SPEECH, "speaking", "TTS status", progress + "%");
                st.event("speak_status", obj("progress", progress));
            }
        });

        if (hdCam == null) return;
        hdCam.setMediaListener(new MediaStreamListener() {
            @Override
            public void getVideoStream(byte[] data) {
                App.get().hdCamera().onVideoFrame(data, HD_W, HD_H);
            }

            @Override
            public void getAudioStream(byte[] data) {
                App.get().hdCamera().onAudioPacket(data);
            }
        });
        hdCam.setMediaListener(new FaceRecognizeListener() {
            @Override
            public void recognizeResult(List<FaceRecognizeBean> list) {
                JSONArray faces = new JSONArray();
                StringBuilder names = new StringBuilder();
                for (FaceRecognizeBean f : list) {
                    faces.put(obj("user", f.getUser(), "gender", f.getGender(), "birthday", f.getBirthday(),
                            "left", f.getLeft(), "top", f.getTop(), "right", f.getRight(), "bottom", f.getBottom()));
                    if (names.length() > 0) names.append(", ");
                    names.append(f.getUser() != null ? f.getUser() : "unknown");
                }
                st.set(RobotState.G_FACE, "count", "Faces seen", list.size());
                st.set(RobotState.G_FACE, "names", "Names", names.toString());
                st.event("faces", obj("faces", faces));
            }
        });
    }

    private void touch(int part, boolean pressed) {
        String label = "#" + part + " " + (part > 0 && part < TOUCH_NAMES.length ? TOUCH_NAMES[part] : "unknown part");
        st.set(RobotState.G_TOUCH, "part_" + part, label, pressed ? "TOUCHED" : "released");
        st.event("touch", obj("part", part, "name", label, "pressed", pressed));
    }

    // ------------------------------------------------------------------ helpers

    private interface SdkCall {
        void run() throws Exception;
    }

    private void safe(String what, SdkCall c) {
        try {
            c.run();
        } catch (Throwable t) {
            EventLog.e(TAG, what + " failed", t);
        }
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static String codeName(int code) {
        switch (code) {
            case 1: return "SUCCESS";
            case -1001: return "NOT_CONNECTED_TO_MAINSERVICE";
            case -1002: return "REMOTE_ERROR";
            case -1003: return "NOT_SYSTEM_APP";
            case -1004: return "FORBIDDEN_IN_ACTIVITY";
            case -1005: return "PARAM_INVALID";
            case -1006: return "ASSETFILE_NOT_EXIST";
            case -1007: return "FORBIDDEN_IN_SERVICE";
            case -1008: return "NO_CHARGE_PILE";
            case -1009: return "CONNECTION_TIMEOUT";
            case -10001: return "ROBOT_IS_INTRODUCING";
            case -10002: return "ROBOT_IS_CHARGING";
            case -10003: return "MOTION_LOCKED";
            case -10004: return "NO_PERMISSION";
            case -10005: return "APP_LOCKED";
            default: return "code " + code;
        }
    }

    /** Converts SDK results into {"ok":bool,"sdk":[{code,name,result}]}. Negative codes are failures. */
    static JSONObject res(OperationResult... results) {
        boolean ok = true;
        JSONArray arr = new JSONArray();
        for (OperationResult r : results) {
            if (r == null) {
                ok = false;
                arr.put(obj("code", 0, "name", "null result"));
                continue;
            }
            if (r.getErrorCode() < 0) ok = false;
            arr.put(obj("code", r.getErrorCode(), "name", codeName(r.getErrorCode()), "result", r.getResult()));
        }
        return obj("ok", ok, "sdk", arr);
    }

    // ------------------------------------------------------------------ head

    public synchronized JSONObject headAbsolute(Integer pan, Integer tilt) {
        OperationResult a = null, b = null;
        if (pan != null) {
            int p = clamp(pan, PAN_MIN, PAN_MAX);
            a = head.doAbsoluteAngleMotion(new AbsoluteAngleHeadMotion(AbsoluteAngleHeadMotion.ACTION_HORIZONTAL, p));
            st.set(RobotState.G_HEAD, "pan_cmd", "Pan (horizontal) commanded", p);
        }
        if (tilt != null) {
            int t = clamp(tilt, TILT_MIN, TILT_MAX);
            b = head.doAbsoluteAngleMotion(new AbsoluteAngleHeadMotion(AbsoluteAngleHeadMotion.ACTION_VERTICAL, t));
            st.set(RobotState.G_HEAD, "tilt_cmd", "Tilt (vertical) commanded", t);
        }
        st.set(RobotState.G_HEAD, "last_cmd", "Last command", "absolute pan=" + pan + " tilt=" + tilt);
        if (a != null && b != null) return res(a, b);
        return a != null ? res(a) : b != null ? res(b) : obj("ok", false, "error", "need pan and/or tilt");
    }

    /** Moves both axes in one MCU command. lock: none|horizontal|vertical|both. */
    public synchronized JSONObject headLocate(int pan, int tilt, String lock) {
        byte a;
        switch (lock == null ? "none" : lock) {
            case "horizontal": a = LocateAbsoluteAngleHeadMotion.ACTION_HORIZONTAL_LOCK; break;
            case "vertical": a = LocateAbsoluteAngleHeadMotion.ACTION_VERTICAL_LOCK; break;
            case "both": a = LocateAbsoluteAngleHeadMotion.ACTION_BOTH_LOCK; break;
            default: a = LocateAbsoluteAngleHeadMotion.ACTION_NO_LOCK;
        }
        int p = clamp(pan, PAN_MIN, PAN_MAX), t = clamp(tilt, TILT_MIN, TILT_MAX);
        OperationResult r = head.doAbsoluteLocateMotion(new LocateAbsoluteAngleHeadMotion(a, p, t));
        st.set(RobotState.G_HEAD, "pan_cmd", "Pan (horizontal) commanded", p);
        st.set(RobotState.G_HEAD, "tilt_cmd", "Tilt (vertical) commanded", t);
        st.set(RobotState.G_HEAD, "last_cmd", "Last command", "locate pan=" + p + " tilt=" + t + " lock=" + lock);
        return res(r);
    }

    private static byte headDir(String dir) {
        switch (dir) {
            case "up": return RelativeAngleHeadMotion.ACTION_UP;
            case "down": return RelativeAngleHeadMotion.ACTION_DOWN;
            case "left": return RelativeAngleHeadMotion.ACTION_LEFT;
            case "right": return RelativeAngleHeadMotion.ACTION_RIGHT;
            case "left_up": return RelativeAngleHeadMotion.ACTION_LEFTUP;
            case "right_up": return RelativeAngleHeadMotion.ACTION_RIGHTUP;
            case "left_down": return RelativeAngleHeadMotion.ACTION_LEFTDOWN;
            case "right_down": return RelativeAngleHeadMotion.ACTION_RIGHTDOWN;
            case "stop": return RelativeAngleHeadMotion.ACTION_STOP;
            default: throw new IllegalArgumentException("unknown head direction: " + dir);
        }
    }

    public synchronized JSONObject headRelative(String dir, int angle) {
        OperationResult r = head.doRelativeAngleMotion(new RelativeAngleHeadMotion(headDir(dir), clamp(angle, 0, 180)));
        st.set(RobotState.G_HEAD, "last_cmd", "Last command", "relative " + dir + " " + angle + " deg");
        return res(r);
    }

    public synchronized JSONObject headCenter() {
        OperationResult r = head.doAbsoluteLocateMotion(
                new LocateAbsoluteAngleHeadMotion(LocateAbsoluteAngleHeadMotion.ACTION_NO_LOCK, PAN_CENTER, TILT_CENTER));
        st.set(RobotState.G_HEAD, "pan_cmd", "Pan (horizontal) commanded", PAN_CENTER);
        st.set(RobotState.G_HEAD, "tilt_cmd", "Tilt (vertical) commanded", TILT_CENTER);
        st.set(RobotState.G_HEAD, "last_cmd", "Last command", "center");
        return res(r);
    }

    public synchronized JSONObject headStop() {
        OperationResult r = head.doRelativeAngleMotion(new RelativeAngleHeadMotion(RelativeAngleHeadMotion.ACTION_STOP, 0));
        st.set(RobotState.G_HEAD, "last_cmd", "Last command", "stop");
        return res(r);
    }

    // ------------------------------------------------------------------ arms (HandMotionManager on SDK 1.1.8)

    private static String sideKey(String side) {
        switch (side) {
            case "left":
            case "right":
            case "both":
                return side;
            default:
                throw new IllegalArgumentException("side must be left|right|both");
        }
    }

    public synchronized JSONObject armAbsolute(String side, int angle, int speed) {
        String s = sideKey(side);
        byte part = s.equals("left") ? AbsoluteAngleHandMotion.PART_LEFT
                : s.equals("right") ? AbsoluteAngleHandMotion.PART_RIGHT : AbsoluteAngleHandMotion.PART_BOTH;
        int a = clamp(angle, ARM_MIN, ARM_MAX);
        OperationResult r = hand.doAbsoluteAngleMotion(new AbsoluteAngleHandMotion(part, clamp(speed, SPEED_MIN, SPEED_MAX), a));
        if (!s.equals("right")) st.set(RobotState.G_ARMS, "left_cmd", "Left arm commanded angle", a);
        if (!s.equals("left")) st.set(RobotState.G_ARMS, "right_cmd", "Right arm commanded angle", a);
        st.set(RobotState.G_ARMS, "last_cmd", "Last command", "absolute " + s + " " + a + " deg");
        return res(r);
    }

    public synchronized JSONObject armRelative(String side, String dir, int angle, int speed) {
        String s = sideKey(side);
        byte part = s.equals("left") ? RelativeAngleHandMotion.PART_LEFT
                : s.equals("right") ? RelativeAngleHandMotion.PART_RIGHT : RelativeAngleHandMotion.PART_BOTH;
        byte action = "up".equals(dir) ? RelativeAngleHandMotion.ACTION_UP : RelativeAngleHandMotion.ACTION_DOWN;
        OperationResult r = hand.doRelativeAngleMotion(
                new RelativeAngleHandMotion(part, clamp(speed, SPEED_MIN, SPEED_MAX), action, clamp(angle, 0, ARM_MAX)));
        st.set(RobotState.G_ARMS, "last_cmd", "Last command", "relative " + s + " " + dir + " " + angle + " deg");
        return res(r);
    }

    /** dir: up|down|stop|reset. up/down keep moving until stop or the limit. */
    public synchronized JSONObject armMove(String side, String dir, int speed) {
        String s = sideKey(side);
        byte part = s.equals("left") ? NoAngleHandMotion.PART_LEFT
                : s.equals("right") ? NoAngleHandMotion.PART_RIGHT : NoAngleHandMotion.PART_BOTH;
        byte action;
        switch (dir) {
            case "up": action = NoAngleHandMotion.ACTION_UP; break;
            case "down": action = NoAngleHandMotion.ACTION_DOWN; break;
            case "stop": action = NoAngleHandMotion.ACTION_STOP; break;
            case "reset": action = NoAngleHandMotion.ACTION_RESET; break;
            default: throw new IllegalArgumentException("dir must be up|down|stop|reset");
        }
        OperationResult r = hand.doNoAngleMotion(new NoAngleHandMotion(part, clamp(speed, SPEED_MIN, SPEED_MAX), action));
        if (action == NoAngleHandMotion.ACTION_RESET) {
            if (!s.equals("right")) st.set(RobotState.G_ARMS, "left_cmd", "Left arm commanded angle", ARM_DOWN);
            if (!s.equals("left")) st.set(RobotState.G_ARMS, "right_cmd", "Right arm commanded angle", ARM_DOWN);
        }
        st.set(RobotState.G_ARMS, "last_cmd", "Last command", "move " + s + " " + dir);
        return res(r);
    }

    // ------------------------------------------------------------------ wheels

    public static final String[] DRIVE_ACTIONS = {"forward", "back", "left", "right", "left_forward", "right_forward",
            "left_back", "right_back", "turn_left", "turn_right", "stop"};

    private static byte wheelAction(String a) {
        switch (a) {
            case "forward": return NoAngleWheelMotion.ACTION_FORWARD_RUN;
            case "back": return NoAngleWheelMotion.ACTION_BACK_RUN;
            case "left": return NoAngleWheelMotion.ACTION_LEFT_CIRCLE;
            case "right": return NoAngleWheelMotion.ACTION_RIGHT_CIRCLE;
            case "left_forward": return NoAngleWheelMotion.ACTION_LEFT_FORWARD_RUN;
            case "right_forward": return NoAngleWheelMotion.ACTION_RIGHT_FORWARD_RUN;
            case "left_back": return NoAngleWheelMotion.ACTION_LEFT_BACK_RUN;
            case "right_back": return NoAngleWheelMotion.ACTION_RIGHT_BACK_RUN;
            case "turn_left": return NoAngleWheelMotion.ACTION_TURN_LEFT;
            case "turn_right": return NoAngleWheelMotion.ACTION_TURN_RIGHT;
            case "stop": return NoAngleWheelMotion.ACTION_STOP_RUN;
            default: throw new IllegalArgumentException("unknown drive action: " + a + " (SDK 1.1.8 has no sideways translation)");
        }
    }

    /**
     * Velocity-style driving. The robot keeps moving only while commands keep arriving:
     * if no drive command is received within timeoutMs the watchdog stops the wheels.
     */
    public synchronized JSONObject drive(String action, int speed, int timeoutMs) {
        byte a = wheelAction(action);
        if (a == NoAngleWheelMotion.ACTION_STOP_RUN) return wheelsStop("drive stop");
        int sp = clamp(speed, SPEED_MIN, SPEED_MAX);
        int timeout = clamp(timeoutMs, 100, 5000);
        driveDeadline = SystemClock.uptimeMillis() + timeout;
        JSONObject out;
        if (!driving || a != driveAction || sp != driveSpeed) {
            OperationResult r = wheel.doNoAngleMotion(new NoAngleWheelMotion(a, (byte) sp));
            // The MainService call can be slow; don't let the watchdog count that time against the client.
            driveDeadline = SystemClock.uptimeMillis() + timeout;
            out = res(r);
            driveAction = a;
            driveSpeed = sp;
            driving = true;
            st.set(RobotState.G_WHEELS, "action", "Current action", action);
            st.set(RobotState.G_WHEELS, "speed", "Speed", sp);
            st.set(RobotState.G_WHEELS, "last_cmd", "Last command", "drive " + action + " speed " + sp);
        } else {
            out = obj("ok", true, "refreshed", true);
        }
        st.set(RobotState.G_WHEELS, "watchdog", "Drive watchdog", "armed, " + timeoutMs + " ms");
        return out;
    }

    /** Relative rotation in place. direction: left|right. */
    public synchronized JSONObject turn(String direction, int degrees, int speed) {
        byte a = "left".equals(direction) ? RelativeAngleWheelMotion.TURN_LEFT : RelativeAngleWheelMotion.TURN_RIGHT;
        OperationResult r = wheel.doRelativeAngleMotion(
                new RelativeAngleWheelMotion(a, clamp(speed, SPEED_MIN, SPEED_MAX), clamp(degrees, 0, 360)));
        st.set(RobotState.G_WHEELS, "last_cmd", "Last command", "turn " + direction + " " + degrees + " deg");
        return res(r);
    }

    public synchronized JSONObject driveDistance(String direction, int cm, int speed) {
        byte a;
        switch (direction) {
            case "forward": a = DistanceWheelMotion.ACTION_FORWARD_RUN; break;
            case "back": a = DistanceWheelMotion.ACTION_BACK_RUN; break;
            case "left_forward": a = DistanceWheelMotion.ACTION_LEFT_FORWARD_RUN; break;
            case "right_forward": a = DistanceWheelMotion.ACTION_RIGHT_FORWARD_RUN; break;
            case "left_back": a = DistanceWheelMotion.ACTION_LEFT_BACK_RUN; break;
            case "right_back": a = DistanceWheelMotion.ACTION_RIGHT_BACK_RUN; break;
            default: throw new IllegalArgumentException("unknown distance direction: " + direction);
        }
        OperationResult r = wheel.doDistanceMotion(new DistanceWheelMotion(a, clamp(speed, SPEED_MIN, SPEED_MAX), clamp(cm, 0, 1000)));
        st.set(RobotState.G_WHEELS, "last_cmd", "Last command", "distance " + direction + " " + cm + " cm");
        return res(r);
    }

    public synchronized JSONObject wheelsStop(String reason) {
        driving = false;
        driveAction = NoAngleWheelMotion.ACTION_STOP_RUN;
        OperationResult a = wheel.doNoAngleMotion(new NoAngleWheelMotion(NoAngleWheelMotion.ACTION_STOP_RUN, (byte) 1));
        OperationResult t = wheel.doNoAngleMotion(new NoAngleWheelMotion(NoAngleWheelMotion.ACTION_STOP_TURN, (byte) 1));
        OperationResult b = wheel.doDistanceMotion(new DistanceWheelMotion(DistanceWheelMotion.ACTION_STOP_RUN, 1, 0));
        OperationResult c = wheel.doRelativeAngleMotion(new RelativeAngleWheelMotion(RelativeAngleWheelMotion.TURN_STOP, 1, 0));
        st.set(RobotState.G_WHEELS, "action", "Current action", "stop");
        st.set(RobotState.G_WHEELS, "last_cmd", "Last command", "stop (" + reason + ")");
        EventLog.i(TAG, "wheels stop: " + reason);
        return res(a, t, b, c);
    }

    public synchronized JSONObject stopAll(String reason) {
        JSONObject w = wheelsStop(reason);
        safe("head stop", this::headStop);
        safe("arms stop", () -> hand.doNoAngleMotion(new NoAngleHandMotion(NoAngleHandMotion.PART_BOTH, 1, NoAngleHandMotion.ACTION_STOP)));
        st.set(RobotState.G_HEAD, "last_cmd", "Last command", "STOP ALL (" + reason + ")");
        st.set(RobotState.G_ARMS, "last_cmd", "Last command", "STOP ALL (" + reason + ")");
        return w;
    }

    public boolean isDriving() {
        return driving;
    }

    // ------------------------------------------------------------------ speech

    // "auto" = plain text with the robot's own voice settings; en/zh send a SpeakOption (SDK 1.1.8 knows only these).
    public static final String[] SPEAK_LANGS = {"auto", "en", "zh"};

    private static int lang(String l) {
        return "zh".equals(l) ? SpeakOption.LAG_CHINESE : SpeakOption.LAG_ENGLISH_US;
    }

    public JSONObject speak(String text, String language, int speed, int intonation) {
        OperationResult r;
        if (language == null || language.isEmpty() || "auto".equals(language)) {
            r = speech.startSpeak(text);
        } else {
            SpeakOption o = new SpeakOption();
            o.setLanguageType(lang(language));
            o.setSpeed(clamp(speed, 0, 100));
            o.setIntonation(clamp(intonation, 0, 100));
            r = speech.startSpeak(text, o);
        }
        st.set(RobotState.G_SPEECH, "speaking", "TTS status", "requested (" + language + "): " + codeName(r == null ? 0 : r.getErrorCode()));
        return res(r);
    }

    public JSONObject stopSpeak() { return res(speech.stopSpeak()); }
    public JSONObject wakeUp() { return res(speech.doWakeUp()); }
    public JSONObject sleep() { return res(speech.doSleep()); }

    // ------------------------------------------------------------------ actuators

    private static byte ledPart(String p) {
        switch (p) {
            case "all": return LED.PART_ALL;
            case "wheel": return LED.PART_WHEEL;
            case "left_hand": return LED.PART_LEFT_HAND;
            case "right_hand": return LED.PART_RIGHT_HAND;
            case "left_head": return LED.PART_LEFT_HEAD;
            case "right_head": return LED.PART_RIGHT_HEAD;
            default: throw new IllegalArgumentException("led part: all|wheel|left_hand|right_hand|left_head|right_head");
        }
    }

    private static byte ledMode(String m) {
        switch (m) {
            case "off": return LED.MODE_CLOSE;
            case "white": return LED.MODE_WHITE;
            case "red": return LED.MODE_RED;
            case "green": return LED.MODE_GREEN;
            case "pink": return LED.MODE_PINK;
            case "purple": return LED.MODE_PURPLE;
            case "blue": return LED.MODE_BLUE;
            case "yellow": return LED.MODE_YELLOW;
            case "flicker_white": return LED.MODE_FLICKER_WHITE;
            case "flicker_red": return LED.MODE_FLICKER_RED;
            case "flicker_green": return LED.MODE_FLICKER_GREEN;
            case "flicker_pink": return LED.MODE_FLICKER_PINK;
            case "flicker_purple": return LED.MODE_FLICKER_PURPLE;
            case "flicker_blue": return LED.MODE_FLICKER_BLUE;
            case "flicker_yellow": return LED.MODE_FLICKER_YELLOW;
            case "flicker_random": return LED.MODE_FLICKER_RANDOM;
            default: throw new IllegalArgumentException("unknown led mode: " + m);
        }
    }

    public static final String[] LED_PARTS = {"all", "wheel", "left_hand", "right_hand", "left_head", "right_head"};
    public static final String[] LED_MODES = {"off", "white", "red", "green", "pink", "purple", "blue", "yellow",
            "flicker_white", "flicker_red", "flicker_green", "flicker_pink", "flicker_purple", "flicker_blue",
            "flicker_yellow", "flicker_random"};

    public JSONObject led(String part, String mode, int delay, int random) {
        OperationResult r = hw.setLED(new LED(ledPart(part), ledMode(mode), (byte) clamp(delay, 1, 127), (byte) clamp(random, 1, 127)));
        st.set(RobotState.G_ACTUATORS, "led_" + part, "LED " + part, mode);
        return res(r);
    }

    public JSONObject whiteLight(boolean on, Integer level) {
        OperationResult a = hw.switchWhiteLight(on);
        st.set(RobotState.G_ACTUATORS, "white_light", "Forehead white light", on ? "on" : "off");
        if (on && level != null) {
            OperationResult b = hw.setWhiteLightLevel(clamp(level, 1, 3));
            st.set(RobotState.G_ACTUATORS, "white_light_level", "Forehead white light level", level);
            return res(a, b);
        }
        return res(a);
    }

    public JSONObject emotion(String name) {
        EmotionsType t = EmotionsType.valueOf(name.toUpperCase(Locale.US));
        st.set(RobotState.G_ACTUATORS, "emotion", "Face emotion", t.name());
        return res(sys.showEmotion(t));
    }

    public JSONObject projector(boolean on) {
        st.set(RobotState.G_ACTUATORS, "projector", "Projector", on ? "on" : "off");
        return res(projector.switchProjector(on));
    }

    public JSONObject wander(boolean on) {
        st.set(RobotState.G_ACTUATORS, "wander", "Built-in wander mode", on ? "on" : "off");
        return res(modular.switchWander(on));
    }

    public JSONObject follow(boolean on) {
        st.set(RobotState.G_ACTUATORS, "follow", "Built-in follow mode", on ? "on" : "off");
        return res(modular.switchFollow(on));
    }

    /** on=true: drive back to the charging dock by itself. */
    public JSONObject autoCharge(boolean on) {
        st.set(RobotState.G_ACTUATORS, "auto_charge", "Go to charging dock", on ? "on" : "off");
        return res(modular.switchCharge(on));
    }

    // ------------------------------------------------------------------ HD camera

    public void setHdUnavailable(String reason) {
        hdError = reason;
    }

    /** Why the last openHdStream() failed. */
    public String hdError() {
        return hdError;
    }

    /** Returns the stream handle, or -1. Frames arrive through the MediaStreamListener as H.264. */
    public int openHdStream() {
        if (hdCam == null) {
            if (hdError == null) hdError = "HD camera manager not available";
            return -1;
        }
        StringBuilder err = new StringBuilder();
        int[] channels = {StreamOption.MAIN_STREAM, StreamOption.SUB_STREAM};
        String[] names = {"main", "sub"};
        for (int i = 0; i < channels.length; i++) {
            try {
                StreamOption o = new StreamOption();
                o.setChannel(channels[i]);
                o.setDecodType(StreamOption.HARDWARE_DECODE);
                o.setJustIframe(false);
                OperationResult r = hdCam.openStream(o);
                String result = r.getResult();
                int h = result == null ? -1 : Integer.parseInt(result.trim());
                if (h >= 0) {
                    EventLog.i(TAG, "HD camera " + names[i] + " stream open, handle " + h);
                    hdError = null;
                    return h;
                }
                err.append(names[i]).append(" stream: native open returned ").append(result).append("; ");
            } catch (Throwable t) {
                err.append(names[i]).append(" stream: ").append(t).append("; ");
            }
            resetHdStream();
        }
        hdError = err + "the robot's local camera stream service did not accept the connection. Listening local ports: "
                + NetUtils.listeningPorts(App.get());
        EventLog.w(TAG, "HD camera openStream failed: " + hdError);
        return -1;
    }

    /** The SDK keeps a failed handle / half-done native init and then never retries; undo that. */
    private void resetHdStream() {
        try {
            Field f = MediaManager.class.getDeclaredField("handle");
            f.setAccessible(true);
            f.setInt(hdCam, -1);
            Method done = MediaManager.class.getDeclaredMethod("done");
            done.setAccessible(true);
            done.invoke(hdCam);
        } catch (Throwable t) {
            EventLog.e(TAG, "HD camera reset failed", t);
        }
    }

    public void closeHdStream(int handle) {
        if (hdCam != null) safe("closeStream", hdCam::closeStream);
    }

    public android.graphics.Bitmap hdSnapshot() {
        try {
            return hdCam == null ? null : hdCam.getVideoImage();
        } catch (Throwable t) {
            return null;
        }
    }
}
