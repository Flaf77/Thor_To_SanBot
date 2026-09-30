package com.thorbridge.sanbot.media;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;

import com.thorbridge.sanbot.App;
import com.thorbridge.sanbot.EventLog;
import com.thorbridge.sanbot.robot.RobotState;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cameras visible to Android itself (tablet camera(s), and on some units the head USB camera).
 * One camera is open at a time; it feeds the on-screen preview and JPEG frames for MJPEG/HTTP.
 */
@SuppressWarnings("deprecation")
public class AndroidCameraHub {

    public static final class Info {
        public final int id;
        public final String facing;
        public final int orientation;

        Info(int id, String facing, int orientation) {
            this.id = id;
            this.facing = facing;
            this.orientation = orientation;
        }

        @Override
        public String toString() {
            return "Camera " + id + " (" + facing + ")";
        }
    }

    private static final int WANT_W = 640, WANT_H = 480;
    private static final int JPEG_QUALITY = 70;

    private final HandlerThread thread = new HandlerThread("android-camera");
    private final Handler handler;
    private final Object frameLock = new Object();

    private Camera camera;
    private int openId = -1;
    private int consumers;
    private SurfaceTexture dummyTexture;
    private SurfaceTexture uiTexture;
    private int pw, ph;
    private byte[] lastJpeg;
    private long lastJpegSeq;
    private long lastJpegAt;
    private int maxFps = 15;
    private long statFrames, statAt;

    public AndroidCameraHub() {
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public List<Info> list() {
        List<Info> out = new ArrayList<>();
        try {
            int n = Camera.getNumberOfCameras();
            for (int i = 0; i < n; i++) {
                Camera.CameraInfo ci = new Camera.CameraInfo();
                Camera.getCameraInfo(i, ci);
                out.add(new Info(i, ci.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "front" : "back", ci.orientation));
            }
        } catch (Exception e) {
            EventLog.e("camera", "cannot list cameras", e);
        }
        return out;
    }

    public int openId() {
        return openId;
    }

    /** Registers a consumer of camera {@code id}. Switches camera if another one is open. */
    public void acquire(final int id) {
        handler.post(() -> {
            consumers++;
            if (openId != id) open(id);
        });
    }

    public void release() {
        handler.post(() -> {
            consumers = Math.max(0, consumers - 1);
            if (consumers == 0) close();
        });
    }

    /** Shows the camera preview on a UI TextureView (null to detach). */
    public void setPreviewTexture(final SurfaceTexture st) {
        handler.post(() -> {
            uiTexture = st;
            if (camera != null) {
                try {
                    camera.stopPreview();
                    camera.setPreviewTexture(st != null ? st : dummy());
                    startPreview();
                } catch (Exception e) {
                    EventLog.e("camera", "preview switch failed", e);
                }
            }
        });
    }

    private SurfaceTexture dummy() {
        if (dummyTexture == null) dummyTexture = new SurfaceTexture(10);
        return dummyTexture;
    }

    private void open(int id) {
        close();
        try {
            camera = Camera.open(id);
            openId = id;
            Camera.Parameters p = camera.getParameters();
            Camera.Size best = null;
            for (Camera.Size s : p.getSupportedPreviewSizes()) {
                if (best == null || Math.abs(s.width * s.height - WANT_W * WANT_H) < Math.abs(best.width * best.height - WANT_W * WANT_H)) {
                    best = s;
                }
            }
            if (best != null) p.setPreviewSize(best.width, best.height);
            p.setPreviewFormat(ImageFormat.NV21);
            List<String> fm = p.getSupportedFocusModes();
            if (fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            camera.setParameters(p);
            Camera.Size s = camera.getParameters().getPreviewSize();
            pw = s.width;
            ph = s.height;
            camera.setPreviewTexture(uiTexture != null ? uiTexture : dummy());
            startPreview();
            EventLog.i("camera", "opened camera " + id + " at " + pw + "x" + ph);
            state("open " + pw + "x" + ph);
        } catch (Exception e) {
            EventLog.e("camera", "cannot open camera " + id + " (it may be in use by the Sanbot system)", e);
            state("ERROR: " + e.getMessage());
            close();
        }
    }

    private void startPreview() {
        int size = pw * ph * 3 / 2;
        camera.setPreviewCallbackWithBuffer(null);
        for (int i = 0; i < 3; i++) camera.addCallbackBuffer(new byte[size]);
        camera.setPreviewCallbackWithBuffer(this::onFrame);
        camera.startPreview();
    }

    private void close() {
        if (camera != null) {
            try {
                camera.setPreviewCallbackWithBuffer(null);
                camera.stopPreview();
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
            if (openId >= 0) state("closed");
        }
        openId = -1;
    }

    private void state(String s) {
        if (openId >= 0) App.get().state().set(RobotState.G_CAMERA, "android_cam_" + openId, "Android camera " + openId, s);
    }

    private void onFrame(byte[] nv21, Camera c) {
        long now = System.currentTimeMillis();
        if (now - lastJpegAt >= 1000 / maxFps) {
            lastJpegAt = now;
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
                new YuvImage(nv21, ImageFormat.NV21, pw, ph, null).compressToJpeg(new Rect(0, 0, pw, ph), JPEG_QUALITY, bos);
                synchronized (frameLock) {
                    lastJpeg = bos.toByteArray();
                    lastJpegSeq++;
                    frameLock.notifyAll();
                }
            } catch (Exception e) {
                EventLog.e("camera", "jpeg encode failed", e);
            }
            statFrames++;
            if (now - statAt >= 2000) {
                state(String.format(Locale.US, "open %dx%d, %.1f fps (jpeg)", pw, ph, statFrames * 1000f / (now - statAt)));
                statFrames = 0;
                statAt = now;
            }
        }
        c.addCallbackBuffer(nv21);
    }

    public long jpegSeq() {
        synchronized (frameLock) {
            return lastJpegSeq;
        }
    }

    /** Blocks until a JPEG newer than {@code afterSeq} is available. Returns {seq, jpeg} or null on timeout. */
    public Object[] waitJpeg(long afterSeq, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (frameLock) {
            while (lastJpegSeq <= afterSeq || lastJpeg == null) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return null;
                frameLock.wait(left);
            }
            return new Object[]{lastJpegSeq, lastJpeg};
        }
    }
}
