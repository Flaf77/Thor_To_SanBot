package com.thorbridge.sanbot.media;

import com.thorbridge.sanbot.App;
import com.thorbridge.sanbot.EventLog;
import com.thorbridge.sanbot.robot.RobotState;
import com.thorbridge.sanbot.robot.SanbotRobot;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The robot's HD head camera, delivered by the Sanbot SDK as an H.264 (Annex-B) stream.
 * The stream is opened only while someone consumes it (tablet preview or a Thor TCP client).
 */
public class HdCameraHub {

    public interface FrameSink {
        void onH264(byte[] data, int width, int height);
    }

    private final CopyOnWriteArrayList<FrameSink> sinks = new CopyOnWriteArrayList<>();
    private SanbotRobot robot;
    private int handle = -1;
    private volatile byte[] configFrame;
    private volatile int width, height;
    private long frames, bytes, audioBytes;
    private long statFrames, statBytes, statAt;

    public synchronized void setRobot(SanbotRobot r) {
        if (r == null && robot != null && handle >= 0) robot.closeHdStream(handle);
        if (r == null) handle = -1;
        robot = r;
        ensureOpen();
    }

    public void addSink(FrameSink s) {
        sinks.addIfAbsent(s);
        ensureOpen();
    }

    public void removeSink(FrameSink s) {
        sinks.remove(s);
        synchronized (this) {
            if (sinks.isEmpty() && handle >= 0 && robot != null) {
                robot.closeHdStream(handle);
                EventLog.i("hdcam", "HD camera stream closed");
                handle = -1;
                App.get().state().set(RobotState.G_CAMERA, "hd_stream", "HD head camera (SDK, H.264)", "idle (no viewers)");
            }
        }
    }

    public int consumers() {
        return sinks.size();
    }

    /** Opens the SDK stream if there are consumers and it is not open yet. Safe to call repeatedly. */
    public synchronized void ensureOpen() {
        if (sinks.isEmpty() || handle >= 0 || robot == null || !robot.isServiceConnected()) return;
        handle = robot.openHdStream();
        EventLog.i("hdcam", "HD camera openStream -> handle " + handle);
        App.get().state().set(RobotState.G_CAMERA, "hd_stream", "HD head camera (SDK, H.264)",
                handle >= 0 ? "open, waiting for frames" : "openStream failed");
    }

    public byte[] configFrame() {
        return configFrame;
    }

    public int width() { return width; }
    public int height() { return height; }

    public void onVideoFrame(byte[] data, int w, int h) {
        if (data == null) return;
        width = w;
        height = h;
        if (containsNal(data, 7)) configFrame = data;
        frames++;
        bytes += data.length;
        for (FrameSink s : sinks) {
            try {
                s.onH264(data, w, h);
            } catch (Exception e) {
                EventLog.e("hdcam", "sink failed", e);
            }
        }
        updateStats(data.length);
    }

    public void onAudioPacket(byte[] data) {
        if (data != null) audioBytes += data.length;
    }

    private void updateStats(int len) {
        statFrames++;
        statBytes += len;
        long now = System.currentTimeMillis();
        if (now - statAt >= 1000) {
            float sec = (now - statAt) / 1000f;
            App.get().state().set(RobotState.G_CAMERA, "hd_stream", "HD head camera (SDK, H.264)",
                    String.format(java.util.Locale.US, "%dx%d  %.1f fps  %.0f kbit/s  viewers=%d",
                            width, height, statFrames / sec, statBytes * 8 / 1000f / sec, sinks.size()));
            App.get().state().set(RobotState.G_AUDIO, "hd_audio", "Head camera audio packets (SDK)", audioBytes + " bytes total");
            statFrames = 0;
            statBytes = 0;
            statAt = now;
        }
    }

    /** True if the Annex-B buffer contains a NAL unit of the given type (5=IDR, 7=SPS, 8=PPS). */
    public static boolean containsNal(byte[] d, int type) {
        for (int i = 0; i + 3 < d.length; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                if ((d[i + 3] & 0x1F) == type) return true;
                i += 2;
            }
        }
        return false;
    }

    public static boolean isKeyFrame(byte[] d) {
        return containsNal(d, 5) || containsNal(d, 7);
    }
}
