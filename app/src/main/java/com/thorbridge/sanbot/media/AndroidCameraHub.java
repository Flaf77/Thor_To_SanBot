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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    private int fmt = ImageFormat.NV21;
    private boolean buffered = true;
    private long framesSinceStart;
    private byte[] nv21Tmp;
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

    /** Closes the open camera now (consumers keep their registration). Returns the id that was open, or -1. */
    public int closeNow() {
        final int[] was = {-1};
        final CountDownLatch done = new CountDownLatch(1);
        handler.post(() -> {
            was[0] = openId;
            close();
            done.countDown();
        });
        try {
            done.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return was[0];
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
            List<Integer> fmts = p.getSupportedPreviewFormats();
            if (fmts == null || fmts.contains(ImageFormat.NV21)) p.setPreviewFormat(ImageFormat.NV21);
            List<String> fm = p.getSupportedFocusModes();
            if (fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            camera.setParameters(p);
            Camera.Parameters a = camera.getParameters();
            Camera.Size s = a.getPreviewSize();
            pw = s.width;
            ph = s.height;
            fmt = a.getPreviewFormat();
            camera.setErrorCallback((err, c) -> {
                String why = err == 2 ? "camera taken over by another app" : err == 100 ? "camera service died" : "error " + err;
                EventLog.w("camera", "camera " + openId + ": " + why);
                state("ERROR from camera driver: " + why);
            });
            buffered = true;
            camera.setPreviewTexture(uiTexture != null ? uiTexture : dummy());
            startPreview();
            EventLog.i("camera", "opened camera " + id + " at " + pw + "x" + ph + " format " + fmt + " (supported " + fmts + ")");
            state("open " + pw + "x" + ph + ", waiting for frames");
        } catch (Exception e) {
            EventLog.e("camera", "cannot open camera " + id + " (it may be in use by the Sanbot system)", e);
            state("ERROR: " + e.getMessage());
            close();
        }
    }

    private void startPreview() {
        camera.setPreviewCallbackWithBuffer(null);
        camera.setPreviewCallback(null);
        if (buffered) {
            // 2 bytes/pixel fits NV21, YV12 and YUY2; a too-small buffer makes the driver drop every frame silently.
            for (int i = 0; i < 3; i++) camera.addCallbackBuffer(new byte[pw * ph * 2]);
            camera.setPreviewCallbackWithBuffer(this::onFrame);
        } else {
            camera.setPreviewCallback(this::onFrame);
        }
        camera.startPreview();
        framesSinceStart = 0;
        handler.removeCallbacks(noFrameCheck);
        handler.postDelayed(noFrameCheck, 3000);
    }

    private final Runnable noFrameCheck = new Runnable() {
        @Override
        public void run() {
            if (camera == null || framesSinceStart > 0) return;
            if (buffered) {
                EventLog.w("camera", "camera " + openId + ": no frames in 3 s, retrying without callback buffers");
                buffered = false;
                try {
                    camera.stopPreview();
                    startPreview();
                    return;
                } catch (Exception e) {
                    EventLog.e("camera", "preview restart failed", e);
                }
            }
            String msg = "open " + pw + "x" + ph + " but the camera driver sends NO frames. The sensor is most likely "
                    + "held by another process (e.g. the Sanbot face/camera service com.hfisone); see 'Exclusive control' on the Connection page";
            EventLog.w("camera", "camera " + openId + ": " + msg);
            state(msg);
        }
    };

    private void close() {
        handler.removeCallbacks(noFrameCheck);
        if (camera != null) {
            try {
                camera.setPreviewCallbackWithBuffer(null);
                camera.setPreviewCallback(null);
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

    private void onFrame(byte[] data, Camera c) {
        framesSinceStart++;
        long now = System.currentTimeMillis();
        if (now - lastJpegAt >= 1000 / maxFps) {
            lastJpegAt = now;
            try {
                byte[] yuv = data;
                int f = fmt;
                if (f == ImageFormat.YV12) {
                    yuv = yv12ToNv21(data);
                    f = ImageFormat.NV21;
                }
                if (f == ImageFormat.NV21 || f == ImageFormat.YUY2) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
                    new YuvImage(yuv, f, pw, ph, null).compressToJpeg(new Rect(0, 0, pw, ph), JPEG_QUALITY, bos);
                    synchronized (frameLock) {
                        lastJpeg = bos.toByteArray();
                        lastJpegSeq++;
                        frameLock.notifyAll();
                    }
                }
            } catch (Exception e) {
                EventLog.e("camera", "jpeg encode failed", e);
            }
            statFrames++;
            if (now - statAt >= 2000) {
                String jpeg = fmt == ImageFormat.NV21 || fmt == ImageFormat.YUY2 || fmt == ImageFormat.YV12
                        ? "jpeg" : "format " + fmt + ", no jpeg for Thor";
                state(String.format(Locale.US, "open %dx%d, %.1f fps (%s)", pw, ph, statFrames * 1000f / (now - statAt), jpeg));
                statFrames = 0;
                statAt = now;
            }
        }
        if (buffered) c.addCallbackBuffer(data);
    }

    /** YV12 (Y, then V and U planes with 16-byte aligned strides) to NV21 (Y, then interleaved VU). */
    private byte[] yv12ToNv21(byte[] in) {
        int yStride = (pw + 15) / 16 * 16;
        int cStride = (yStride / 2 + 15) / 16 * 16;
        int cw = pw / 2, ch = ph / 2;
        if (nv21Tmp == null || nv21Tmp.length != pw * ph * 3 / 2) nv21Tmp = new byte[pw * ph * 3 / 2];
        byte[] out = nv21Tmp;
        for (int r = 0; r < ph; r++) System.arraycopy(in, r * yStride, out, r * pw, pw);
        int vBase = yStride * ph, uBase = vBase + cStride * ch, o = pw * ph;
        for (int r = 0; r < ch; r++) {
            for (int col = 0; col < cw; col++) {
                out[o++] = in[vBase + r * cStride + col];
                out[o++] = in[uBase + r * cStride + col];
            }
        }
        return out;
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
