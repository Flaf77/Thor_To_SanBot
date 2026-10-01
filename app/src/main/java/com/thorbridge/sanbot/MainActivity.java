package com.thorbridge.sanbot;

import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.qihancloud.opensdk.base.TopBaseActivity;
import com.qihancloud.opensdk.beans.FuncConstant;
import com.qihancloud.opensdk.function.unit.HandMotionManager;
import com.qihancloud.opensdk.function.unit.HardWareManager;
import com.qihancloud.opensdk.function.unit.HeadMotionManager;
import com.qihancloud.opensdk.function.unit.MediaManager;
import com.qihancloud.opensdk.function.unit.ModularMotionManager;
import com.qihancloud.opensdk.function.unit.ProjectorManager;
import com.qihancloud.opensdk.function.unit.SpeechManager;
import com.qihancloud.opensdk.function.unit.SystemManager;
import com.qihancloud.opensdk.function.unit.WheelMotionManager;
import com.thorbridge.sanbot.net.BridgeService;
import com.thorbridge.sanbot.robot.MainServiceConnectionFix;
import com.thorbridge.sanbot.robot.RobotState;
import com.thorbridge.sanbot.robot.SanbotRobot;
import com.thorbridge.sanbot.ui.ConnectionPage;
import com.thorbridge.sanbot.ui.ModulesPage;
import com.thorbridge.sanbot.ui.MotorsPage;
import com.thorbridge.sanbot.ui.Page;
import com.thorbridge.sanbot.ui.Ui;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The only activity. It must extend the Sanbot TopBaseActivity: the Sanbot system only
 * talks to the app whose activity is in the foreground, so keep this app open on the robot.
 */
public class MainActivity extends TopBaseActivity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService cmdExec = Executors.newSingleThreadExecutor();
    private final Map<ServiceConnection, ServiceConnection> wrappedConnections = new HashMap<>();
    private SanbotRobot robot;
    private Page[] pages;
    private Button[] tabs;
    private int current = -1;
    private TextView status, battery, banner;
    private Button estop;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onCreate(savedInstanceState);

        App app = App.get();
        MediaManager media = null;
        try {
            media = (MediaManager) getUnitManager(FuncConstant.MEDIA_MANAGER);
        } catch (Throwable t) {
            EventLog.e("main", "HD camera manager unavailable (libuvcNative)", t);
        }
        try {
            robot = new SanbotRobot(app.state(),
                    (HardWareManager) getUnitManager(FuncConstant.HARDWARE_MANAGER),
                    (SystemManager) getUnitManager(FuncConstant.SYSTEM_MANAGER),
                    (HeadMotionManager) getUnitManager(FuncConstant.HEADMOTION_MANAGER),
                    (HandMotionManager) getUnitManager(FuncConstant.HANDMOTION_MANAGER),
                    (WheelMotionManager) getUnitManager(FuncConstant.WHEELMOTION_MANAGER),
                    (SpeechManager) getUnitManager(FuncConstant.SPEECH_MANAGER),
                    media,
                    (ProjectorManager) getUnitManager(FuncConstant.PROJECTOR_MANAGER),
                    (ModularMotionManager) getUnitManager(FuncConstant.MODULARMOTION_MANAGER));
            robot.start();
            app.setRobot(robot);
        } catch (Throwable t) {
            EventLog.e("main", "Sanbot SDK init failed (is this a Sanbot robot?)", t);
            app.state().set(RobotState.G_ROBOT, "sdk", "Sanbot SDK", "INIT FAILED: " + t);
        }

        BridgeService.start(this);
        setContentView(buildUi());
        showPage(0);
        ui.post(ticker);
    }

    @Override
    protected void onMainServiceConnected() {
        if (robot != null) robot.onMainServiceConnected();
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        if (service != null && "com.sunbo.MainService".equals(service.getAction()) && conn != null) {
            ServiceConnection fix = new MainServiceConnectionFix(this, conn, this::onMainServiceConnected);
            wrappedConnections.put(conn, fix);
            conn = fix;
        }
        return super.bindService(service, conn, flags);
    }

    @Override
    public void unbindService(ServiceConnection conn) {
        ServiceConnection fix = wrappedConnections.remove(conn);
        super.unbindService(fix != null ? fix : conn);
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (pages != null) for (Page p : pages) p.onDestroy();
        App.get().setRobot(null);
        if (robot != null) robot.stop();
        cmdExec.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout top = Ui.hbox(this);
        int p = Ui.dp(this, 8);
        top.setPadding(p, p, p, p);
        TextView title = Ui.text(this, "Sanbot  <->  Thor", 20, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title);
        status = Ui.text(this, "", 15, Ui.DIM);
        status.setPadding(Ui.dp(this, 20), 0, 0, 0);
        top.addView(status, Ui.weight(1));
        battery = Ui.text(this, "", 15, Ui.TEXT);
        battery.setPadding(0, 0, Ui.dp(this, 16), 0);
        top.addView(battery);
        estop = Ui.coloredButton(this, "E-STOP", Ui.BAD, v -> toggleEstop());
        top.addView(estop, new LinearLayout.LayoutParams(Ui.dp(this, 160), Ui.dp(this, 56)));
        root.addView(top);

        banner = Ui.text(this, "", 22, Ui.WARN);
        banner.setGravity(Gravity.CENTER);
        banner.setVisibility(View.GONE);
        root.addView(banner);

        pages = new Page[]{new ModulesPage(this), new MotorsPage(this), new ConnectionPage(this)};
        String[] names = {"Modules & sensors", "Motors", "Connection to Thor"};
        LinearLayout tabBar = Ui.hbox(this);
        tabs = new Button[pages.length];
        for (int i = 0; i < pages.length; i++) {
            final int idx = i;
            tabs[i] = Ui.button(this, names[i], v -> showPage(idx));
            tabBar.addView(tabs[i], Ui.weight(1));
        }
        root.addView(tabBar);

        FrameLayout content = new FrameLayout(this);
        for (Page pg : pages) {
            View v = pg.view();
            v.setVisibility(View.GONE);
            content.addView(v);
        }
        root.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private void showPage(int idx) {
        if (idx == current) return;
        if (current >= 0) {
            pages[current].onHide();
            pages[current].view().setVisibility(View.GONE);
        }
        current = idx;
        pages[idx].view().setVisibility(View.VISIBLE);
        pages[idx].onShow();
        for (int i = 0; i < tabs.length; i++) tabs[i].setAlpha(i == idx ? 1f : 0.55f);
        pages[idx].refresh();
    }

    private void toggleEstop() {
        final boolean on = !App.get().dispatcher().isEstopLatched();
        cmdExec.submit(() -> App.get().dispatcher().setEstop(on));
        estop.setText(on ? "E-STOP LATCHED\n(tap to release)" : "E-STOP");
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            App app = App.get();
            boolean sdk = robot != null && robot.isServiceConnected();
            boolean thor = app.bridge().thorConnected();
            status.setText("SDK: " + (sdk ? "connected" : "not connected") + "    Thor: " + (thor ? "CONNECTED" : "not connected"));
            status.setTextColor(sdk && thor ? Ui.OK : Ui.WARN);
            Object b = app.state().get(RobotState.G_POWER, "battery_percent");
            battery.setText(b == null ? "Battery: ?" : "Battery: " + b + "%");
            String bt = app.dispatcher().screenText();
            banner.setText(bt);
            banner.setVisibility(bt.isEmpty() ? View.GONE : View.VISIBLE);
            if (current >= 0) {
                try {
                    pages[current].refresh();
                } catch (Exception e) {
                    EventLog.e("ui", "refresh failed", e);
                }
            }
            ui.postDelayed(this, 250);
        }
    };

    /** Runs a command from the tablet UI off the main thread and shows the result on the Motors page. */
    public void runCmd(final String cmd, final Object... kv) {
        cmdExec.submit(() -> {
            final JSONObject ack = App.get().dispatcher().tablet(cmd, kv);
            final boolean ok = ack.optBoolean("ok");
            if (!"wheels.drive".equals(cmd) || !ok) {
                ui.post(() -> ((MotorsPage) pages[1]).showResult(cmd, ack.toString()));
            }
            if (!ok && !"wheels.drive".equals(cmd)) {
                final String why = ack.has("error") ? ack.optString("error") : String.valueOf(ack.optJSONObject("result"));
                ui.post(() -> Toast.makeText(this, cmd + " failed: " + why, Toast.LENGTH_LONG).show());
            }
        });
    }
}
