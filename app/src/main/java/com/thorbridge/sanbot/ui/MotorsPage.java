package com.thorbridge.sanbot.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.qihancloud.opensdk.function.beans.EmotionsType;
import com.thorbridge.sanbot.MainActivity;
import com.thorbridge.sanbot.robot.RobotState;
import com.thorbridge.sanbot.robot.SanbotRobot;

import java.util.ArrayList;
import java.util.List;

/** State of every motor / actuator plus manual controls. */
public class MotorsPage extends Page {

    private final List<GroupView> groups = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout wheelSpeed;
    private Runnable driveRepeat;

    public MotorsPage(MainActivity act) {
        super(act);
    }

    @Override
    protected View build(Context c) {
        ScrollView sv = new ScrollView(c);
        LinearLayout root = Ui.vbox(c);
        sv.addView(root);

        CheckBox allow = Ui.check(c, "Allow Thor to move the motors (uncheck = Thor can only read sensors)",
                app.prefs().allowRemoteControl());
        allow.setOnCheckedChangeListener((b, v) -> app.prefs().setAllowRemoteControl(v));
        root.addView(allow);

        LinearLayout cols = Ui.hbox(c);
        cols.setGravity(android.view.Gravity.TOP);
        LinearLayout left = Ui.vbox(c), right = Ui.vbox(c);
        cols.addView(left, Ui.weight(1));
        cols.addView(right, Ui.weight(1));
        root.addView(cols);

        left.addView(headCard(c));
        left.addView(armsCard(c));
        right.addView(wheelsCard(c));
        left.addView(actuatorsCard(c));
        return sv;
    }

    private LinearLayout withState(Context c, String group, String title) {
        GroupView gv = new GroupView(c, group, title);
        groups.add(gv);
        return gv.card;
    }

    // ------------------------------------------------------------------ head

    private View headCard(Context c) {
        LinearLayout card = withState(c, RobotState.G_HEAD, "Head (pan / tilt)");
        card.addView(Ui.slider(c, "Pan (horizontal)", SanbotRobot.PAN_MIN, SanbotRobot.PAN_MAX, SanbotRobot.PAN_CENTER,
                v -> act.runCmd("head.absolute", "pan", v)));
        card.addView(Ui.slider(c, "Tilt (vertical)", SanbotRobot.TILT_MIN, SanbotRobot.TILT_MAX, SanbotRobot.TILT_CENTER,
                v -> act.runCmd("head.absolute", "tilt", v)));
        LinearLayout row = Ui.hbox(c);
        row.addView(Ui.button(c, "Up 5", v -> act.runCmd("head.relative", "direction", "up", "angle", 5)));
        row.addView(Ui.button(c, "Down 5", v -> act.runCmd("head.relative", "direction", "down", "angle", 5)));
        row.addView(Ui.button(c, "Left 15", v -> act.runCmd("head.relative", "direction", "left", "angle", 15)));
        row.addView(Ui.button(c, "Right 15", v -> act.runCmd("head.relative", "direction", "right", "angle", 15)));
        row.addView(Ui.button(c, "Center", v -> act.runCmd("head.center")));
        row.addView(Ui.button(c, "Stop", v -> act.runCmd("head.stop")));
        card.addView(row);
        return card;
    }

    // ------------------------------------------------------------------ arms

    private View armsCard(Context c) {
        LinearLayout card = withState(c, RobotState.G_ARMS, "Arms");
        final LinearLayout speed = Ui.slider(c, "Arm speed", 1, 10, 5, null);
        card.addView(speed);
        card.addView(Ui.slider(c, "Left arm angle", SanbotRobot.ARM_MIN, SanbotRobot.ARM_MAX, SanbotRobot.ARM_DOWN,
                v -> act.runCmd("arm.absolute", "side", "left", "angle", v, "speed", Ui.sliderValue(speed, 1))));
        card.addView(Ui.slider(c, "Right arm angle", SanbotRobot.ARM_MIN, SanbotRobot.ARM_MAX, SanbotRobot.ARM_DOWN,
                v -> act.runCmd("arm.absolute", "side", "right", "angle", v, "speed", Ui.sliderValue(speed, 1))));
        LinearLayout row = Ui.hbox(c);
        row.addView(Ui.button(c, "Both forward (90)", v -> act.runCmd("arm.absolute", "side", "both", "angle", 90, "speed", Ui.sliderValue(speed, 1))));
        row.addView(Ui.button(c, "Both down (180)", v -> act.runCmd("arm.absolute", "side", "both", "angle", 180, "speed", Ui.sliderValue(speed, 1))));
        row.addView(Ui.button(c, "Reset", v -> act.runCmd("arm.move", "side", "both", "direction", "reset")));
        row.addView(Ui.button(c, "Stop", v -> act.runCmd("arm.move", "side", "both", "direction", "stop")));
        card.addView(row);
        card.addView(Ui.text(c, "Angle 0 = arm up, 180 = arm down along the body (Sanbot Elf).", 12, Ui.DIM));
        return card;
    }

    // ------------------------------------------------------------------ wheels

    private View wheelsCard(Context c) {
        LinearLayout card = withState(c, RobotState.G_WHEELS, "Wheels");
        wheelSpeed = Ui.slider(c, "Drive speed", 1, 10, 3, null);
        card.addView(wheelSpeed);
        card.addView(Ui.text(c, "Hold a button to drive, release to stop:", 13, Ui.DIM));

        GridLayout pad = new GridLayout(c);
        pad.setColumnCount(3);
        String[][] layout = {
                {"left_forward", "forward", "right_forward"},
                {"turn_left", "STOP", "turn_right"},
                {"left_back", "back", "right_back"}};
        String[][] labels = {
                {"Fwd-left", "Forward", "Fwd-right"},
                {"Turn left", "STOP", "Turn right"},
                {"Back-left", "Back", "Back-right"}};
        for (int r = 0; r < 3; r++) {
            for (int col = 0; col < 3; col++) {
                String action = layout[r][col];
                Button b;
                if ("STOP".equals(action)) {
                    b = Ui.coloredButton(c, "STOP", Ui.BAD, v -> act.runCmd("wheels.stop"));
                } else {
                    b = Ui.button(c, labels[r][col], null);
                    holdToDrive(b, action);
                }
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = Ui.dp(c, 120);
                lp.height = Ui.dp(c, 64);
                lp.setMargins(4, 4, 4, 4);
                pad.addView(b, lp);
            }
        }
        card.addView(pad);

        final EditText deg = Ui.edit(c, "degrees", "90", true);
        LinearLayout turn = Ui.hbox(c);
        turn.addView(Ui.text(c, "Turn by angle: ", 14, Ui.TEXT));
        turn.addView(deg, new LinearLayout.LayoutParams(Ui.dp(c, 80), LinearLayout.LayoutParams.WRAP_CONTENT));
        turn.addView(Ui.button(c, "Left", v -> act.runCmd("wheels.turn", "direction", "left", "angle", Ui.parseInt(deg, 90), "speed", speed())));
        turn.addView(Ui.button(c, "Right", v -> act.runCmd("wheels.turn", "direction", "right", "angle", Ui.parseInt(deg, 90), "speed", speed())));
        card.addView(turn);

        final EditText cm = Ui.edit(c, "cm", "50", true);
        LinearLayout dist = Ui.hbox(c);
        dist.addView(Ui.text(c, "Move distance (cm): ", 14, Ui.TEXT));
        dist.addView(cm, new LinearLayout.LayoutParams(Ui.dp(c, 80), LinearLayout.LayoutParams.WRAP_CONTENT));
        dist.addView(Ui.button(c, "Forward", v -> act.runCmd("wheels.distance", "direction", "forward", "cm", Ui.parseInt(cm, 50), "speed", speed())));
        dist.addView(Ui.button(c, "Back", v -> act.runCmd("wheels.distance", "direction", "back", "cm", Ui.parseInt(cm, 50), "speed", speed())));
        card.addView(dist);
        return card;
    }

    private int speed() {
        return Ui.sliderValue(wheelSpeed, 1);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void holdToDrive(Button b, final String action) {
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Otherwise the ScrollView steals the touch on the slightest finger move and sends CANCEL (= stop).
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    stopRepeat();
                    driveRepeat = new Runnable() {
                        @Override
                        public void run() {
                            act.runCmd("wheels.drive", "action", action, "speed", speed(), "timeout_ms", 1000);
                            ui.postDelayed(this, 250);
                        }
                    };
                    driveRepeat.run();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    stopRepeat();
                    act.runCmd("wheels.stop");
                    return true;
                default:
                    return true;
            }
        });
    }

    private void stopRepeat() {
        if (driveRepeat != null) ui.removeCallbacks(driveRepeat);
        driveRepeat = null;
    }

    // ------------------------------------------------------------------ LEDs, light, projector, emotion

    private View actuatorsCard(Context c) {
        LinearLayout card = withState(c, RobotState.G_ACTUATORS, "LEDs, light, projector, face");
        final Spinner part = Ui.spinner(c, SanbotRobot.LED_PARTS);
        final Spinner mode = Ui.spinner(c, SanbotRobot.LED_MODES);
        LinearLayout led = Ui.hbox(c);
        led.addView(Ui.text(c, "LED ", 14, Ui.TEXT));
        led.addView(part);
        led.addView(mode);
        led.addView(Ui.button(c, "Apply", v -> act.runCmd("led", "part", part.getSelectedItem(), "mode", mode.getSelectedItem())));
        card.addView(led);

        LinearLayout light = Ui.hbox(c);
        light.addView(Ui.button(c, "White light on", v -> act.runCmd("white_light", "on", true, "level", 2)));
        light.addView(Ui.button(c, "White light off", v -> act.runCmd("white_light", "on", false)));
        light.addView(Ui.button(c, "Projector on", v -> act.runCmd("projector", "on", true)));
        light.addView(Ui.button(c, "Projector off", v -> act.runCmd("projector", "on", false)));
        card.addView(light);

        EmotionsType[] em = EmotionsType.values();
        String[] names = new String[em.length];
        for (int i = 0; i < em.length; i++) names[i] = em[i].name().toLowerCase(java.util.Locale.US);
        final Spinner emotion = Ui.spinner(c, names);
        LinearLayout face = Ui.hbox(c);
        face.addView(Ui.text(c, "Face ", 14, Ui.TEXT));
        face.addView(emotion);
        face.addView(Ui.button(c, "Show", v -> act.runCmd("emotion", "name", emotion.getSelectedItem())));
        card.addView(face);

        LinearLayout modes = Ui.hbox(c);
        modes.addView(Ui.button(c, "Go to charging dock", v -> act.runCmd("charge", "on", true)));
        modes.addView(Ui.button(c, "Cancel docking", v -> act.runCmd("charge", "on", false)));
        modes.addView(Ui.button(c, "Wander off", v -> act.runCmd("wander", "on", false)));
        card.addView(modes);
        return card;
    }

    @Override
    public void refresh() {
        for (GroupView g : groups) g.refresh();
    }

    @Override
    public void onHide() {
        if (driveRepeat != null) {
            stopRepeat();
            act.runCmd("wheels.stop");
        }
    }
}
