package com.thorbridge.sanbot.ui;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.thorbridge.sanbot.MainActivity;
import com.thorbridge.sanbot.media.AndroidCameraHub;
import com.thorbridge.sanbot.media.H264Decoder;
import com.thorbridge.sanbot.media.MicStreamer;
import com.thorbridge.sanbot.robot.RobotState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Every module of the robot: cameras, microphones, speaker and all sensors with live values. */
public class ModulesPage extends Page {

    private final List<GroupView> groups = new ArrayList<>();
    private H264Decoder decoder;
    private SurfaceView hdView;
    private boolean hdOn;
    private Button hdBtn;
    private TextView hdInfo;

    private TextureView camView;
    private Spinner camSpinner;
    private Button camBtn;
    private boolean camOn;
    private TextView camInfo;

    private ProgressBar micBar;
    private TextView micText;
    private Button micBtn;
    private boolean micOn;
    private final MicStreamer.PcmSink micMonitor = chunk -> { };

    public ModulesPage(MainActivity act) {
        super(act);
    }

    @Override
    protected View build(Context c) {
        ScrollView sv = new ScrollView(c);
        LinearLayout root = Ui.vbox(c);
        sv.addView(root);

        root.addView(Ui.text(c, "All robot modules. Green values were updated in the last 2 seconds. "
                + "Entries appear automatically when the Sanbot system reports data.", 13, Ui.DIM));

        LinearLayout media = Ui.hbox(c);
        media.setGravity(android.view.Gravity.TOP);
        LinearLayout mediaL = Ui.vbox(c), mediaR = Ui.vbox(c);
        media.addView(mediaL, Ui.weight(1));
        media.addView(mediaR, Ui.weight(1));
        root.addView(media);

        mediaL.addView(buildHdCard(c));
        mediaR.addView(buildAndroidCamCard(c));
        mediaL.addView(buildMicCard(c));
        mediaR.addView(buildSpeakerCard(c));

        LinearLayout cols = Ui.hbox(c);
        cols.setGravity(android.view.Gravity.TOP);
        LinearLayout left = Ui.vbox(c), right = Ui.vbox(c);
        cols.addView(left, Ui.weight(1));
        cols.addView(right, Ui.weight(1));
        root.addView(cols);
        int i = 0;
        for (RobotState.Group g : RobotState.GROUPS) {
            if (g.motor) continue;
            GroupView gv = new GroupView(c, g);
            groups.add(gv);
            (i++ % 2 == 0 ? left : right).addView(gv.card);
        }
        return sv;
    }

    // ------------------------------------------------------------------ HD head camera (Sanbot SDK)

    private View buildHdCard(Context c) {
        LinearLayout card = Ui.card(c, "HD head camera (Sanbot SDK)");
        decoder = new H264Decoder(app.hdCamera());
        hdView = new SurfaceView(c);
        hdView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                if (hdOn) decoder.attach(h.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                decoder.detach();
            }
        });
        card.addView(hdView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 250)));
        hdInfo = Ui.text(c, "", 13, Ui.DIM);
        card.addView(hdInfo);
        hdBtn = Ui.button(c, "Start preview", v -> setHd(!hdOn));
        card.addView(hdBtn);
        card.addView(Ui.text(c, "Thor receives this camera as raw H.264 on TCP port 9101.", 12, Ui.DIM));
        return card;
    }

    private void setHd(boolean on) {
        hdOn = on;
        hdBtn.setText(on ? "Stop preview" : "Start preview");
        if (on && hdView.getHolder().getSurface().isValid()) decoder.attach(hdView.getHolder().getSurface());
        if (!on) decoder.detach();
    }

    // ------------------------------------------------------------------ Android cameras

    private View buildAndroidCamCard(Context c) {
        LinearLayout card = Ui.card(c, "Tablet cameras (Android)");
        List<AndroidCameraHub.Info> list = app.cameras().list();
        if (list.isEmpty()) {
            card.addView(Ui.text(c, "Android reports no cameras.", 14, Ui.WARN));
            return card;
        }
        camSpinner = Ui.spinner(c, list.toArray());
        card.addView(camSpinner);
        camView = new TextureView(c);
        camView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                if (camOn) app.cameras().setPreviewTexture(st);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                app.cameras().setPreviewTexture(null);
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        card.addView(camView, new LinearLayout.LayoutParams(Ui.dp(c, 330), Ui.dp(c, 248)));
        camInfo = Ui.text(c, "", 13, Ui.DIM);
        card.addView(camInfo);
        camBtn = Ui.button(c, "Start preview", v -> setCam(!camOn));
        card.addView(camBtn);
        card.addView(Ui.text(c, "Thor: http://<tablet-ip>:8080/camera/<id>.mjpg", 12, Ui.DIM));
        return card;
    }

    private void setCam(boolean on) {
        if (camSpinner == null || on == camOn) return;
        camOn = on;
        camBtn.setText(on ? "Stop preview" : "Start preview");
        if (on) {
            AndroidCameraHub.Info info = (AndroidCameraHub.Info) camSpinner.getSelectedItem();
            app.cameras().acquire(info.id);
            if (camView.isAvailable()) app.cameras().setPreviewTexture(camView.getSurfaceTexture());
        } else {
            app.cameras().setPreviewTexture(null);
            app.cameras().release();
        }
    }

    // ------------------------------------------------------------------ microphone / speaker

    private View buildMicCard(Context c) {
        LinearLayout card = Ui.card(c, "Microphone (tablet, Android)");
        micBar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        micBar.setMax(90);
        card.addView(micBar, Ui.matchWidth());
        micText = Ui.text(c, "", 14, Ui.TEXT);
        card.addView(micText);
        micBtn = Ui.button(c, "Start level meter", v -> setMic(!micOn));
        card.addView(micBtn);
        card.addView(Ui.text(c, "The Sanbot mic array (wake word, sound direction, speech recognition) is used by the "
                + "Sanbot system; its results appear under 'Microphones & speaker' and 'Speech'. "
                + "Thor receives tablet mic audio as 16 kHz mono s16le PCM on TCP port 9102.", 12, Ui.DIM));
        return card;
    }

    private void setMic(boolean on) {
        if (on == micOn) return;
        micOn = on;
        micBtn.setText(on ? "Stop level meter" : "Start level meter");
        if (on) app.mic().addSink(micMonitor);
        else app.mic().removeSink(micMonitor);
    }

    private View buildSpeakerCard(Context c) {
        LinearLayout card = Ui.card(c, "Speaker & voice");
        final EditText text = Ui.edit(c, "Text for the robot to say", "Hello, I am connected to Thor.", false);
        card.addView(text, Ui.matchWidth());
        final String[] langs = {"en", "de", "fr", "es", "it", "pt", "pl", "tr", "da", "ja", "ko", "zh", "ar"};
        final Spinner lang = Ui.spinner(c, langs);
        LinearLayout row = Ui.hbox(c);
        row.addView(lang);
        row.addView(Ui.button(c, "Speak (Sanbot TTS)", v -> act.runCmd("speak", "text", text.getText().toString(),
                "lang", langs[lang.getSelectedItemPosition()])));
        row.addView(Ui.button(c, "Stop", v -> act.runCmd("speak.stop")));
        row.addView(Ui.button(c, "Test tone", v -> app.speaker().testTone()));
        card.addView(row);
        card.addView(Ui.slider(c, "Volume %", 0, 100, 60, v -> act.runCmd("volume", "percent", v)));
        card.addView(Ui.text(c, "Thor can speak through the robot with the 'speak' command, or stream its own "
                + "voice as 16 kHz mono s16le PCM to TCP port 9103.", 12, Ui.DIM));
        return card;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void refresh() {
        for (GroupView g : groups) g.refresh();
        Object hd = app.state().get(RobotState.G_CAMERA, "hd_stream");
        hdInfo.setText(hd == null ? (hdOn ? "waiting for the Sanbot SDK..." : "stopped") : String.valueOf(hd));
        if (camInfo != null && camSpinner != null) {
            AndroidCameraHub.Info info = (AndroidCameraHub.Info) camSpinner.getSelectedItem();
            Object s = app.state().get(RobotState.G_CAMERA, "android_cam_" + info.id);
            camInfo.setText(s == null ? "stopped" : String.valueOf(s));
        }
        float db = app.mic().levelDb();
        micBar.setProgress((int) Math.max(0, db + 90));
        micText.setText(app.mic().isRunning() ? String.format(Locale.US, "%.1f dBFS", db) : "idle");
    }

    @Override
    public void onHide() {
        setHd(false);
        setCam(false);
        setMic(false);
    }

    @Override
    public void onDestroy() {
        onHide();
    }
}
