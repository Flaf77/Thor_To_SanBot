package com.thorbridge.sanbot.media;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.thorbridge.sanbot.App;
import com.thorbridge.sanbot.EventLog;
import com.thorbridge.sanbot.robot.RobotState;

import java.io.IOException;
import java.io.InputStream;

/** Plays raw PCM from Thor (16 kHz, mono, s16le) on the robot speaker. */
public class SpeakerSink {

    public static final int SAMPLE_RATE = 16000;

    private volatile Thread current;

    private static AudioTrack newTrack() {
        int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SAMPLE_RATE), AudioTrack.MODE_STREAM);
    }

    /** Blocks until the stream ends. A new stream interrupts the previous one. */
    public void play(InputStream in, String from) throws IOException {
        Thread me = Thread.currentThread();
        Thread prev = current;
        current = me;
        if (prev != null) prev.interrupt();
        AudioTrack t = newTrack();
        App.get().state().set(RobotState.G_AUDIO, "speaker", "Speaker (PCM from Thor)", "playing from " + from);
        try {
            t.play();
            byte[] buf = new byte[3200];
            int n;
            while (current == me && !me.isInterrupted() && (n = in.read(buf)) > 0) {
                t.write(buf, 0, n & ~1);
            }
        } finally {
            t.stop();
            t.release();
            if (current == me) current = null;
            App.get().state().set(RobotState.G_AUDIO, "speaker", "Speaker (PCM from Thor)", "idle");
        }
    }

    /** 1 s 440 Hz test tone. */
    public void testTone() {
        new Thread(() -> {
            AudioTrack t = newTrack();
            short[] s = new short[SAMPLE_RATE];
            for (int i = 0; i < s.length; i++) s[i] = (short) (Math.sin(2 * Math.PI * 440 * i / SAMPLE_RATE) * 12000);
            try {
                t.play();
                t.write(s, 0, s.length);
                Thread.sleep(1100);
            } catch (Exception e) {
                EventLog.e("speaker", "tone failed", e);
            } finally {
                t.stop();
                t.release();
            }
        }, "tone").start();
    }
}
