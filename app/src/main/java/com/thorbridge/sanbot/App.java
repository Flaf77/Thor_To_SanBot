package com.thorbridge.sanbot;

import android.app.Application;

import com.thorbridge.sanbot.media.AndroidCameraHub;
import com.thorbridge.sanbot.media.HdCameraHub;
import com.thorbridge.sanbot.media.MicStreamer;
import com.thorbridge.sanbot.media.SpeakerSink;
import com.thorbridge.sanbot.net.Bridge;
import com.thorbridge.sanbot.robot.CommandDispatcher;
import com.thorbridge.sanbot.robot.RobotState;
import com.thorbridge.sanbot.robot.SanbotRobot;

/** Process-wide singletons shared by the UI, the robot layer and the network bridge. */
public class App extends Application {

    private static App instance;

    private Prefs prefs;
    private RobotState state;
    private CommandDispatcher dispatcher;
    private HdCameraHub hdCamera;
    private AndroidCameraHub cameras;
    private MicStreamer mic;
    private SpeakerSink speaker;
    private Bridge bridge;
    private volatile SanbotRobot robot;

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        if (CrashActivity.isCrashProcess()) return;
        CrashActivity.install(this);
        prefs = new Prefs(this);
        state = new RobotState();
        dispatcher = new CommandDispatcher(this);
        hdCamera = new HdCameraHub();
        cameras = new AndroidCameraHub();
        mic = new MicStreamer(state);
        speaker = new SpeakerSink();
        bridge = new Bridge(this);
        EventLog.i("app", "Sanbot Thor Bridge started");
    }

    public Prefs prefs() { return prefs; }
    public RobotState state() { return state; }
    public CommandDispatcher dispatcher() { return dispatcher; }
    public HdCameraHub hdCamera() { return hdCamera; }
    public AndroidCameraHub cameras() { return cameras; }
    public MicStreamer mic() { return mic; }
    public SpeakerSink speaker() { return speaker; }
    public Bridge bridge() { return bridge; }

    /** Null while the Sanbot SDK is not attached (MainActivity not alive). */
    public SanbotRobot robot() { return robot; }

    public void setRobot(SanbotRobot r) {
        robot = r;
        hdCamera.setRobot(r);
    }
}
