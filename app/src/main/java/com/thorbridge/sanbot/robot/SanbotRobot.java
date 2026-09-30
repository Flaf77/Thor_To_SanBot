package com.thorbridge.sanbot.robot;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.sanbot.opensdk.beans.OperationResult;
import com.sanbot.opensdk.function.beans.EmotionsType;
import com.sanbot.opensdk.function.beans.FaceRecognizeBean;
import com.sanbot.opensdk.function.beans.LED;
import com.sanbot.opensdk.function.beans.SpeakOption;
import com.sanbot.opensdk.function.beans.StreamOption;
import com.sanbot.opensdk.function.beans.hardware.MotorDefend;
import com.sanbot.opensdk.function.beans.hardware.MotorLock;
import com.sanbot.opensdk.function.beans.headmotion.AbsoluteAngleHeadMotion;
import com.sanbot.opensdk.function.beans.headmotion.LocateAbsoluteAngleHeadMotion;
import com.sanbot.opensdk.function.beans.headmotion.RelativeAngleHeadMotion;
import com.sanbot.opensdk.function.beans.speech.Grammar;
import com.sanbot.opensdk.function.beans.speech.RecognizeTextBean;
import com.sanbot.opensdk.function.beans.speech.SpeakStatus;
import com.sanbot.opensdk.function.beans.wheelmotion.DistanceWheelMotion;
import com.sanbot.opensdk.function.beans.wheelmotion.NoAngleWheelMotion;
import com.sanbot.opensdk.function.beans.wheelmotion.RelativeAngleWheelMotion;
import com.sanbot.opensdk.function.beans.wing.AbsoluteAngleWingMotion;
import com.sanbot.opensdk.function.beans.wing.NoAngleWingMotion;
import com.sanbot.opensdk.function.beans.wing.RelativeAngleWingMotion;
import com.sanbot.opensdk.function.unit.HDCameraManager;
import com.sanbot.opensdk.function.unit.HardWareManager;
import com.sanbot.opensdk.function.unit.HeadMotionManager;
import com.sanbot.opensdk.function.unit.ModularMotionManager;
import com.sanbot.opensdk.function.unit.ProjectorManager;
import com.sanbot.opensdk.function.unit.SpeechManager;
import com.sanbot.opensdk.function.unit.SystemManager;
import com.sanbot.opensdk.function.unit.WheelMotionManager;
import com.sanbot.opensdk.function.unit.WingMotionManager;
import com.sanbot.opensdk.function.unit.interfaces.hardware.ChargeStatusListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.GravityDataListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.GyroscopeListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.HandStatusListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.IWakeSignalListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.InfrareListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.McuConnectStatusListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.MotorListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.ObstacleListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.PIRListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.TouchSensorListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.UltrasonicListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.VoiceLocateListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.WheelStatusListener;
import com.sanbot.opensdk.function.unit.interfaces.hardware.WhiteLightBrightnessListener;
import com.sanbot.opensdk.function.unit.interfaces.media.FaceRecognizeListener;
import com.sanbot.opensdk.function.unit.interfaces.media.MediaStreamListener;
import com.sanbot.opensdk.function.unit.interfaces.speech.RecognizeListener;
import com.sanbot.opensdk.function.unit.interfaces.speech.SpeakListener;
import com.sanbot.opensdk.function.unit.interfaces.speech.WakenListener;
import com.sanbot.opensdk.function.unit.interfaces.system.KeyStatusListener;
import com.sanbot.opensdk.function.unit.interfaces.system.ObstacleStatusListener;
import com.sanbot.opensdk.function.unit.interfaces.system.WheelObstacleStatusListener;
import com.thorbridge.sanbot.App;
import com.thorbridge.sanbot.EventLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

import static com.thorbridge.sanbot.robot.RobotState.obj;

/**
 * Hardware abstraction over the Sanbot OpenSDK managers (SDK 2.0.1.x, Sanbot Elf / S1).
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
    private final WingMotionManager wing;
    private final WheelMotionManager wheel;
    private final SpeechManager speech;
    private final HDCameraManager hdCam;
    private final ProjectorManager projector;
    private final ModularMotionManager modular;

    private final HandlerThread thread = new HandlerThread("robot-poll");
    private Handler handler;
    private volatile boolean serviceConnected;

    // Drive watchdog state
    private volatile boolean driving;
    private volatile byte driveAction = NoAngleWheelMotion.ACTION_STOP;
    private volatile int driveSpeed;
    private volatile long driveDeadline;

    public SanbotRobot(RobotState state, HardWareManager hw, SystemManager sys, HeadMotionManager head,
                       WingMotionManager wing, WheelMotionManager wheel, SpeechManager speech,
                       HDCameraManager hdCam, ProjectorManager projector, ModularMotionManager modular) {
        this.st = state;
        this.hw = hw;
        this.sys = sys;
        this.head = head;
        this.wing = wing;
        this.wheel = wheel;
        this.speech = speech;
        this.hdCam = hdCam;
        this.projector = projector;
        this.modular = modular;
        defineEntries();
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        thread.start();
        handler = new Handler(thread.getLooper());
        registerListeners();
        handler.post(poller);
        handler.post(watchdog);
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
        st.set(RobotState.G_ROBOT, "sdk", "Sanbot SDK", "connected to MainService");
        EventLog.i(TAG, "Sanbot MainService connected");
        handler.post(new Runnable() {
            @Override
            public void run() {
                readStaticInfo();
                safe("queryPir", () -> {
                    hw.queryPirStatus(1);
                    hw.queryPirStatus(2);
                });
                safe("queryWhiteLight", hw::queryWhiteLightBrightness);
                App.get().hdCamera().ensureOpen();
            }
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
        st.define(RobotState.G_IMU, "gravity", "Gravity sensor");
        st.define(RobotState.G_AUDIO, "voice_angle", "Sound source angle (mic array)");
        st.define(RobotState.G_SPEECH, "listening", "Recognizer");
        st.define(RobotState.G_SPEECH, "last_text", "Last recognized text");
        st.define(RobotState.G_SPEECH, "speaking", "TTS status");
        st.define(RobotState.G_OBSTACLE, "front_obstacle", "Obstacle (IR bumper)");
        st.define(RobotState.G_FACE, "count", "Faces seen");
        st.define(RobotState.G_HEAD, "pan_cmd", "Pan (horizontal) commanded");
        st.define(RobotState.G_HEAD, "tilt_cmd", "Tilt (vertical) commanded");
        st.define(RobotState.G_HEAD, "last_cmd", "Last command");
        st.define(RobotState.G_ARMS, "left_cmd", "Left arm commanded angle");
        st.define(RobotState.G_ARMS, "right_cmd", "Right arm commanded angle");
        st.define(RobotState.G_ARMS, "hand_status", "Arm status (MCU)");
        st.define(RobotState.G_ARMS, "last_cmd", "Last command");
        st.define(RobotState.G_WHEELS, "status", "Wheel status (MCU)");
        st.define(RobotState.G_WHEELS, "action", "Current action");
        st.define(RobotState.G_WHEELS, "speed", "Speed");
        st.define(RobotState.G_WHEELS, "watchdog", "Drive watchdog");
        st.define(RobotState.G_WHEELS, "last_cmd", "Last command");
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
            if (serviceConnected) {
                safe("ultrasonic", hw::queryUltronicData);
                if (pollTick % 2 == 0) safe("gravity", hw::queryGravityData);
            }
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

            @Override
            public void onTouch(int part, boolean pressed) {
                touch(part, pressed);
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
        hw.setOnHareWareListener(new UltrasonicListener() {
            @Override
            public void onUltrasonicResult(int num, int data) {
                st.set(RobotState.G_ULTRASONIC, "us_" + num, "Ultrasonic #" + num, data == 65535 ? "error / no echo" : data);
            }
        });
        hw.setOnHareWareListener(new GyroscopeListener() {
            @Override
            public void gyroscopeCheckResult(boolean a, boolean b) {
                st.set(RobotState.G_IMU, "self_check", "Gyro self-check", a + " / " + b);
            }

            @Override
            public void gyroscopeData(float x, float y, float z) {
                st.set(RobotState.G_IMU, "gyro_x", "Gyro / heading X", x);
                st.set(RobotState.G_IMU, "gyro_y", "Gyro Y", y);
                st.set(RobotState.G_IMU, "gyro_z", "Gyro Z", z);
            }
        });
        hw.setOnHareWareListener(new GravityDataListener() {
            @Override
            public void onGravityDataResult(float v) {
                st.set(RobotState.G_IMU, "gravity", "Gravity sensor", v);
            }
        });
        hw.setOnHareWareListener(new VoiceLocateListener() {
            @Override
            public void voiceLocateResult(int angle) {
                st.set(RobotState.G_AUDIO, "voice_angle", "Sound source angle (mic array)", angle);
                st.event("voice_locate", obj("angle", angle));
            }
        });
        hw.setOnHareWareListener(new ObstacleListener() {
            @Override
            public void onObstacleStatus(boolean blocked) {
                st.set(RobotState.G_OBSTACLE, "front_obstacle", "Obstacle (IR bumper)", blocked ? "BLOCKED" : "clear");
                st.event("obstacle", obj("blocked", blocked));
            }
        });
        hw.setOnHareWareListener(new IWakeSignalListener() {
            @Override
            public void onWakeSignal() {
                st.set(RobotState.G_MISC, "wake_signal", "MCU wake signal", "received");
                st.event("wake_signal", obj());
            }
        });
        hw.setOnHareWareListener(new HandStatusListener() {
            @Override
            public void onHandStatus(byte part, byte status) {
                st.set(RobotState.G_ARMS, "hand_status", "Arm status (MCU)", "part " + part + " status " + status);
            }
        });
        hw.setOnHareWareListener(new WheelStatusListener() {
            @Override
            public void onWheelStatus(int s) {
                st.set(RobotState.G_WHEELS, "status", "Wheel status (MCU)", s == 0 ? "stopped" : "running (" + s + ")");
            }
        });
        hw.setOnHareWareListener(new ChargeStatusListener() {
            @Override
            public void onChargeStatus(int a, int b) {
                st.set(RobotState.G_POWER, "charge_event", "Charge status event", a + " / " + b);
                st.event("charge_status", obj("a", a, "b", b));
            }
        });
        hw.setOnHareWareListener(new McuConnectStatusListener() {
            @Override
            public void onMcuStatus(int a, int b) {
                st.set(RobotState.G_MISC, "mcu_link", "MCU link status", a + " / " + b);
            }
        });
        hw.setOnHareWareListener(new WhiteLightBrightnessListener() {
            @Override
            public void onWhiteLightBrightness(byte level) {
                st.set(RobotState.G_ACTUATORS, "white_light_level", "Forehead white light level", (int) level);
            }
        });
        hw.setOnHareWareListener(new MotorListener() {
            @Override
            public void onMotorCheckResult(int a, int b) {
                st.set(RobotState.G_LOCKS, "motor_check", "Motor self-check", a + " / " + b);
            }
        });

        sys.setKeyStatusListener(new KeyStatusListener() {
            @Override
            public void onKeyStatus(int key, String status) {
                st.set(RobotState.G_MISC, "key_" + key, "Button " + key, status);
                st.event("key", obj("key", key, "status", status));
            }
        });
        sys.setOnObstacleStatusListener(new ObstacleStatusListener() {
            @Override
            public void onObstacleStatus(int which, int status) {
                st.set(RobotState.G_OBSTACLE, "obstacle_" + which, "Obstacle sensor " + which, status);
                st.event("obstacle_status", obj("which", which, "status", status));
            }
        });
        sys.setOnWheelObstacleStatusListener(new WheelObstacleStatusListener() {
            @Override
            public void onWheelObstacleStatus(int which, int status) {
                st.set(RobotState.G_OBSTACLE, "wheel_obstacle_" + which, "Wheel obstacle " + which, status);
                st.event("wheel_obstacle", obj("which", which, "status", status));
            }
        });

        wheel.setWheelMotionListener(new WheelMotionManager.WheelMotionListener() {
            @Override
            public void onWheelStatus(String s) {
                st.set(RobotState.G_WHEELS, "motion_event", "Motion event", s);
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
            public void onRecognizeText(RecognizeTextBean b) {
                st.set(RobotState.G_SPEECH, "partial_text", "Partial text", b.getText());
                st.event("speech_partial", obj("text", b.getText(), "last", b.isLast()));
            }

            @Override
            public void onRecognizeVolume(int v) {
                st.set(RobotState.G_AUDIO, "asr_volume", "Mic volume (Sanbot ASR, 0-30)", v);
            }

            @Override
            public void onStartRecognize() {
                st.set(RobotState.G_SPEECH, "listening", "Recognizer", "listening");
            }

            @Override
            public void onStopRecognize() {
                st.set(RobotState.G_SPEECH, "listening", "Recognizer", "stopped");
            }

            @Override
            public void onError(int a, int b) {
                st.set(RobotState.G_SPEECH, "error", "Recognizer error", a + " / " + b);
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

            @Override
            public void onWakeUpStatus(boolean b) {
                st.set(RobotState.G_SPEECH, "awake", "Wake state", b ? "awake" : "sleeping");
            }
        });
        speech.setOnSpeechListener(new SpeakListener() {
            @Override
            public void onSpeakStatus(SpeakStatus s) {
                if (s == null) return;
                st.set(RobotState.G_SPEECH, "speaking", "TTS status",
                        String.format(Locale.US, "%.0f%% \"%s\"", s.getProgress(), s.getText()));
                st.event("speak_status", obj("id", s.getId(), "progress", s.getProgress(), "text", s.getText()));
            }
        });

        hdCam.setMediaListener(new MediaStreamListener() {
            @Override
            public void getVideoStream(int handle, byte[] data, int width, int height) {
                App.get().hdCamera().onVideoFrame(data, width, height);
            }

            @Override
            public void getAudioStream(int handle, byte[] data) {
                App.get().hdCamera().onAudioPacket(data);
            }
        });
        hdCam.setMediaListener(new FaceRecognizeListener() {
            @Override
            public void recognizeResult(List<FaceRecognizeBean> list) {
                JSONArray faces = new JSONArray();
                StringBuilder names = new StringBuilder();
                for (FaceRecognizeBean f : list) {
                    faces.put(obj("user", f.getUser(), "gender", f.getGender(), "age", f.getAge(),
                            "left", f.getLeft(), "top", f.getTop(), "right", f.getRight(), "bottom", f.getBottom(),
                            "yaw", f.getYaw(), "pitch", f.getPitch(), "roll", f.getRoll()));
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
        OperationResult r = head.doResetMotion();
        st.set(RobotState.G_HEAD, "pan_cmd", "Pan (horizontal) commanded", PAN_CENTER);
        st.set(RobotState.G_HEAD, "tilt_cmd", "Tilt (vertical) commanded", TILT_CENTER);
        st.set(RobotState.G_HEAD, "last_cmd", "Last command", "reset / center");
        return res(r);
    }

    public synchronized JSONObject headStop() {
        OperationResult r = head.doStopMotion();
        st.set(RobotState.G_HEAD, "last_cmd", "Last command", "stop");
        return res(r);
    }

    // ------------------------------------------------------------------ arms (wings on the Elf)

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
        byte part = s.equals("left") ? AbsoluteAngleWingMotion.PART_LEFT
                : s.equals("right") ? AbsoluteAngleWingMotion.PART_RIGHT : AbsoluteAngleWingMotion.PART_BOTH;
        int a = clamp(angle, ARM_MIN, ARM_MAX);
        OperationResult r = wing.doAbsoluteAngleMotion(new AbsoluteAngleWingMotion(part, clamp(speed, SPEED_MIN, SPEED_MAX), a));
        if (!s.equals("right")) st.set(RobotState.G_ARMS, "left_cmd", "Left arm commanded angle", a);
        if (!s.equals("left")) st.set(RobotState.G_ARMS, "right_cmd", "Right arm commanded angle", a);
        st.set(RobotState.G_ARMS, "last_cmd", "Last command", "absolute " + s + " " + a + " deg");
        return res(r);
    }

    public synchronized JSONObject armRelative(String side, String dir, int angle, int speed) {
        String s = sideKey(side);
        byte part = s.equals("left") ? RelativeAngleWingMotion.PART_LEFT
                : s.equals("right") ? RelativeAngleWingMotion.PART_RIGHT : RelativeAngleWingMotion.PART_BOTH;
        byte action = "up".equals(dir) ? RelativeAngleWingMotion.ACTION_UP : RelativeAngleWingMotion.ACTION_DOWN;
        OperationResult r = wing.doRelativeAngleMotion(
                new RelativeAngleWingMotion(part, clamp(speed, SPEED_MIN, SPEED_MAX), action, clamp(angle, 0, ARM_MAX)));
        st.set(RobotState.G_ARMS, "last_cmd", "Last command", "relative " + s + " " + dir + " " + angle + " deg");
        return res(r);
    }

    /** dir: up|down|stop|reset. up/down keep moving until stop or the limit. */
    public synchronized JSONObject armMove(String side, String dir, int speed) {
        String s = sideKey(side);
        byte part = s.equals("left") ? NoAngleWingMotion.PART_LEFT
                : s.equals("right") ? NoAngleWingMotion.PART_RIGHT : NoAngleWingMotion.PART_BOTH;
        byte action;
        switch (dir) {
            case "up": action = NoAngleWingMotion.ACTION_UP; break;
            case "down": action = NoAngleWingMotion.ACTION_DOWN; break;
            case "stop": action = NoAngleWingMotion.ACTION_STOP; break;
            case "reset": action = NoAngleWingMotion.ACTION_RESET; break;
            default: throw new IllegalArgumentException("dir must be up|down|stop|reset");
        }
        OperationResult r = wing.doNoAngleMotion(new NoAngleWingMotion(part, clamp(speed, SPEED_MIN, SPEED_MAX), action));
        if (action == NoAngleWingMotion.ACTION_RESET) {
            if (!s.equals("right")) st.set(RobotState.G_ARMS, "left_cmd", "Left arm commanded angle", ARM_DOWN);
            if (!s.equals("left")) st.set(RobotState.G_ARMS, "right_cmd", "Right arm commanded angle", ARM_DOWN);
        }
        st.set(RobotState.G_ARMS, "last_cmd", "Last command", "move " + s + " " + dir);
        return res(r);
    }

    // ------------------------------------------------------------------ wheels

    private static byte wheelAction(String a) {
        switch (a) {
            case "forward": return NoAngleWheelMotion.ACTION_FORWARD;
            case "back": return NoAngleWheelMotion.ACTION_BACK;
            case "left": return NoAngleWheelMotion.ACTION_LEFT;
            case "right": return NoAngleWheelMotion.ACTION_RIGHT;
            case "left_forward": return NoAngleWheelMotion.ACTION_LEFT_FORWARD;
            case "right_forward": return NoAngleWheelMotion.ACTION_RIGHT_FORWARD;
            case "left_back": return NoAngleWheelMotion.ACTION_LEFT_BACK;
            case "right_back": return NoAngleWheelMotion.ACTION_RIGHT_BACK;
            case "left_translation": return NoAngleWheelMotion.ACTION_LEFT_TRANSLATION;
            case "right_translation": return NoAngleWheelMotion.ACTION_RIGHT_TRANSLATION;
            case "turn_left": return NoAngleWheelMotion.ACTION_TURN_LEFT;
            case "turn_right": return NoAngleWheelMotion.ACTION_TURN_RIGHT;
            case "stop": return NoAngleWheelMotion.ACTION_STOP;
            default: throw new IllegalArgumentException("unknown drive action: " + a);
        }
    }

    /**
     * Velocity-style driving. The robot keeps moving only while commands keep arriving:
     * if no drive command is received within timeoutMs the watchdog stops the wheels.
     */
    public synchronized JSONObject drive(String action, int speed, int timeoutMs) {
        byte a = wheelAction(action);
        if (a == NoAngleWheelMotion.ACTION_STOP) return wheelsStop("drive stop");
        int sp = clamp(speed, SPEED_MIN, SPEED_MAX);
        driveDeadline = SystemClock.uptimeMillis() + clamp(timeoutMs, 100, 5000);
        JSONObject out;
        if (!driving || a != driveAction || sp != driveSpeed) {
            OperationResult r = wheel.doNoAngleMotion(new NoAngleWheelMotion(a, sp));
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
            case "left_translation": a = DistanceWheelMotion.ACTION_LEFT_TRANSLATION; break;
            case "right_translation": a = DistanceWheelMotion.ACTION_RIGHT_TRANSLATION; break;
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
        driveAction = NoAngleWheelMotion.ACTION_STOP;
        OperationResult a = wheel.doNoAngleMotion(new NoAngleWheelMotion(NoAngleWheelMotion.ACTION_STOP, 1));
        OperationResult b = wheel.doDistanceMotion(new DistanceWheelMotion(DistanceWheelMotion.ACTION_STOP_RUN, 1, 0));
        OperationResult c = wheel.doRelativeAngleMotion(new RelativeAngleWheelMotion(RelativeAngleWheelMotion.TURN_STOP, 1, 0));
        st.set(RobotState.G_WHEELS, "action", "Current action", "stop");
        st.set(RobotState.G_WHEELS, "last_cmd", "Last command", "stop (" + reason + ")");
        EventLog.i(TAG, "wheels stop: " + reason);
        return res(a, b, c);
    }

    public synchronized JSONObject stopAll(String reason) {
        JSONObject w = wheelsStop(reason);
        safe("head stop", head::doStopMotion);
        safe("arms stop", () -> wing.doNoAngleMotion(new NoAngleWingMotion(NoAngleWingMotion.PART_BOTH, 1, NoAngleWingMotion.ACTION_STOP)));
        st.set(RobotState.G_HEAD, "last_cmd", "Last command", "STOP ALL (" + reason + ")");
        st.set(RobotState.G_ARMS, "last_cmd", "Last command", "STOP ALL (" + reason + ")");
        return w;
    }

    public boolean isDriving() {
        return driving;
    }

    // ------------------------------------------------------------------ speech

    private static int lang(String l) {
        if (l == null) return SpeakOption.LAG_ENGLISH_US;
        switch (l) {
            case "zh": return SpeakOption.LAG_CHINESE;
            case "es": return SpeakOption.LAG_SPANISH_SPAIN;
            case "fr": return SpeakOption.LAG_FRENCH_FRANCE;
            case "pt": return SpeakOption.LAG_PORTUGUESE_PORTUGAL;
            case "ar": return SpeakOption.LAG_ARABIC_INTERNATIONAL;
            case "ja": return SpeakOption.LAG_JAPANESE;
            case "it": return SpeakOption.LAG_ITALIAN;
            case "pl": return SpeakOption.LAG_POLISH;
            case "tr": return SpeakOption.LAG_TURKISH;
            case "da": return SpeakOption.LAG_DANISH;
            case "ko": return SpeakOption.LAG_KOREAN;
            case "de": return SpeakOption.LAG_GERMAN;
            default: return SpeakOption.LAG_ENGLISH_US;
        }
    }

    public JSONObject speak(String text, String language, int speed, int intonation) {
        SpeakOption o = new SpeakOption();
        o.setLanguageType(lang(language));
        o.setSpeed(clamp(speed, 0, 100));
        o.setIntonation(clamp(intonation, 0, 100));
        return res(speech.startSpeak(text, o));
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

    private static byte lockPart(String p) {
        switch (p) {
            case "head": return MotorLock.PART_HEAD;
            case "neck": return MotorLock.PART_NECK;
            case "head_and_neck": return MotorLock.PART_HEAD_AND_NECK;
            case "left_hand": return MotorLock.PART_LEFT_HAND;
            case "right_hand": return MotorLock.PART_RIGHT_HAND;
            case "both_hands": return MotorLock.PART_BOTH_HAND;
            case "wheel": return MotorLock.PART_WHEEL;
            default: throw new IllegalArgumentException("part: head|neck|head_and_neck|left_hand|right_hand|both_hands|wheel");
        }
    }

    public static final String[] LOCK_PARTS = {"head_and_neck", "head", "neck", "both_hands", "left_hand", "right_hand", "wheel"};

    public JSONObject motorLock(String part, boolean lock) {
        OperationResult r = hw.lockMotor(new MotorLock(lockPart(part), lock ? MotorLock.LOCK : MotorLock.UNLOCK));
        st.set(RobotState.G_LOCKS, "lock_" + part, "Lock " + part, lock ? "LOCKED" : "unlocked");
        return res(r);
    }

    public JSONObject motorDefend(String part, boolean on) {
        OperationResult r = hw.switchMotorDefend(new MotorDefend(lockPart(part), on ? MotorDefend.OPEN : MotorDefend.CLOSE));
        st.set(RobotState.G_LOCKS, "defend_" + part, "Protection " + part, on ? "on" : "off");
        return res(r);
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

    public JSONObject queryUltrasonic() { return res(hw.queryUltronicData()); }
    public JSONObject queryPir() { return res(hw.queryPirStatus(1), hw.queryPirStatus(2)); }

    // ------------------------------------------------------------------ HD camera

    /** Returns the stream handle, or -1. Frames arrive through the MediaStreamListener as H.264. */
    public int openHdStream() {
        StreamOption o = new StreamOption();
        o.setChannel(StreamOption.MAIN_STREAM);
        o.setDecodType(StreamOption.HARDWARE_DECODE);
        o.setJustIframe(false);
        OperationResult r = hdCam.openStream(o);
        try {
            return Integer.parseInt(r.getResult());
        } catch (Exception e) {
            EventLog.w(TAG, "openStream failed: " + codeName(r.getErrorCode()) + " " + r.getResult());
            return -1;
        }
    }

    public void closeHdStream(int handle) {
        safe("closeStream", () -> hdCam.closeStream(handle));
    }

    public android.graphics.Bitmap hdSnapshot() {
        try {
            return hdCam.getVideoImage();
        } catch (Throwable t) {
            return null;
        }
    }
}
