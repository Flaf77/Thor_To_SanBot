package com.thorbridge.sanbot.media;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import com.thorbridge.sanbot.EventLog;
import com.thorbridge.sanbot.robot.RobotState;

import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tablet microphone capture: 16 kHz, mono, signed 16-bit little-endian PCM in 20 ms chunks.
 * Runs only while there is a consumer (UI level meter or a Thor TCP client).
 */
public class MicStreamer {

    public interface PcmSink {
        void onPcm(byte[] chunk);
    }

    public static final int SAMPLE_RATE = 16000;
    private static final int CHUNK_BYTES = SAMPLE_RATE / 50 * 2;

    private final RobotState st;
    private final CopyOnWriteArrayList<PcmSink> sinks = new CopyOnWriteArrayList<>();
    private Thread thread;
    private volatile boolean running;
    private volatile float levelDb = -90;

    public MicStreamer(RobotState st) {
        this.st = st;
        st.define(RobotState.G_AUDIO, "mic", "Tablet microphone (Android)");
    }

    public float levelDb() {
        return levelDb;
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void addSink(PcmSink s) {
        sinks.addIfAbsent(s);
        running = true;
        if (thread == null) {
            thread = new Thread(this::loop, "mic");
            thread.start();
        }
    }

    public synchronized void removeSink(PcmSink s) {
        sinks.remove(s);
        if (sinks.isEmpty()) running = false;
    }

    private void loop() {
        AudioRecord rec = null;
        boolean failed = false;
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK_BYTES * 10));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord not initialized (mic busy? Sanbot voice service may hold it)");
            }
            rec.startRecording();
            st.set(RobotState.G_AUDIO, "mic", "Tablet microphone (Android)", "recording 16 kHz mono");
            EventLog.i("mic", "microphone started");
            byte[] buf = new byte[CHUNK_BYTES];
            long statAt = 0;
            while (running) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                byte[] chunk = n == buf.length ? buf.clone() : java.util.Arrays.copyOf(buf, n);
                double sum = 0;
                for (int i = 0; i + 1 < n; i += 2) {
                    int s = (short) ((chunk[i] & 0xFF) | (chunk[i + 1] << 8));
                    sum += (double) s * s;
                }
                double rms = Math.sqrt(sum / Math.max(1, n / 2));
                levelDb = (float) (20 * Math.log10(Math.max(rms, 1) / 32768.0));
                for (PcmSink s : sinks) s.onPcm(chunk);
                long now = System.currentTimeMillis();
                if (now - statAt > 500) {
                    statAt = now;
                    st.set(RobotState.G_AUDIO, "mic_level", "Mic level (dBFS)", String.format(Locale.US, "%.1f", levelDb));
                }
            }
        } catch (Exception e) {
            failed = true;
            EventLog.e("mic", "microphone error", e);
            st.set(RobotState.G_AUDIO, "mic", "Tablet microphone (Android)", "ERROR: " + e.getMessage());
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Exception ignored) {
                }
                rec.release();
            }
            levelDb = -90;
            if (!failed) st.set(RobotState.G_AUDIO, "mic", "Tablet microphone (Android)", "idle");
            EventLog.i("mic", "microphone stopped");
            synchronized (this) {
                thread = null;
                running = false;
                // A consumer was added while this thread was shutting down.
                if (!failed && !sinks.isEmpty()) addSink(sinks.get(0));
            }
        }
    }
}
