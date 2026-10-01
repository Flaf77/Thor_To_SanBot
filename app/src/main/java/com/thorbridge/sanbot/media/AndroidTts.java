package com.thorbridge.sanbot.media;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.thorbridge.sanbot.EventLog;
import com.thorbridge.sanbot.robot.RobotState;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static com.thorbridge.sanbot.robot.RobotState.obj;

/** Android's own text-to-speech engine, played on the tablet speaker (works without the Sanbot speech service). */
public class AndroidTts {

    private static final String KEY = "android_tts";
    private static final String LABEL = "Android TTS";

    private final RobotState st;
    private final TextToSpeech tts;
    private final AtomicInteger ids = new AtomicInteger();
    private volatile boolean ready;
    private volatile String error;

    public AndroidTts(Context ctx, RobotState state) {
        st = state;
        st.set(RobotState.G_SPEECH, KEY, LABEL, "starting");
        tts = new TextToSpeech(ctx.getApplicationContext(), this::onInit);
    }

    private void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            error = "no Android TTS engine available (init status " + status + ")";
            st.set(RobotState.G_SPEECH, KEY, LABEL, "ERROR: " + error);
            EventLog.w("tts", error);
            return;
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
                st.set(RobotState.G_SPEECH, "speaking", "TTS status", "speaking (Android TTS)");
                st.event("speak_status", obj("engine", "android", "progress", 0));
            }

            @Override
            public void onDone(String id) {
                st.set(RobotState.G_SPEECH, "speaking", "TTS status", "finished");
                st.event("speak_status", obj("engine", "android", "progress", 100, "finished", true));
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onError(String id) {
                st.set(RobotState.G_SPEECH, "speaking", "TTS status", "Android TTS error");
            }
        });
        ready = true;
        StringBuilder engines = new StringBuilder();
        for (TextToSpeech.EngineInfo e : tts.getEngines()) {
            if (engines.length() > 0) engines.append(", ");
            engines.append(e.name);
        }
        String info = "ready, engine " + tts.getDefaultEngine() + " (installed: " + engines + ")";
        st.set(RobotState.G_SPEECH, KEY, LABEL, info);
        EventLog.i("tts", "Android TTS " + info);
    }

    /** lang: auto (engine default) | en | zh | any BCP-47 tag such as "de-DE". */
    public JSONObject speak(String text, String lang, int speed, int pitch) {
        if (!ready) return obj("ok", false, "error", error != null ? error : "Android TTS is still starting");
        if (lang != null && !lang.isEmpty() && !"auto".equals(lang)) {
            Locale loc = "en".equals(lang) ? Locale.US : "zh".equals(lang) ? Locale.CHINA : Locale.forLanguageTag(lang);
            int r = tts.setLanguage(loc);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                return obj("ok", false, "error", "Android TTS engine has no voice for '" + lang + "'");
            }
        }
        // speed/pitch 0..100 with 50 = normal, mapped to 0.5x..2x
        tts.setSpeechRate((float) Math.pow(2, (clamp(speed) - 50) / 50.0));
        tts.setPitch((float) Math.pow(2, (clamp(pitch) - 50) / 50.0));
        String id = "u" + ids.incrementAndGet();
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), id);
        return obj("ok", r == TextToSpeech.SUCCESS, "engine", "android", "utterance", id);
    }

    public JSONObject stop() {
        if (ready) tts.stop();
        return obj("ok", true);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }
}
