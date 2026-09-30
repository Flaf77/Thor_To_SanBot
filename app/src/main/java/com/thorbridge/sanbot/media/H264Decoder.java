package com.thorbridge.sanbot.media;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import com.thorbridge.sanbot.EventLog;

import java.nio.ByteBuffer;

/** Decodes the HD camera H.264 stream onto a Surface for the on-tablet preview. */
public class H264Decoder implements HdCameraHub.FrameSink {

    private final HdCameraHub hub;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private Surface surface;
    private MediaCodec codec;
    private int w, h;
    private boolean needKey = true;

    public H264Decoder(HdCameraHub hub) {
        this.hub = hub;
    }

    public synchronized void attach(Surface s) {
        release();
        surface = s;
        hub.addSink(this);
    }

    public synchronized void detach() {
        hub.removeSink(this);
        release();
        surface = null;
    }

    @Override
    public synchronized void onH264(byte[] data, int width, int height) {
        if (surface == null || !surface.isValid()) return;
        try {
            if (codec == null || width != w || height != h) {
                release();
                w = width;
                h = height;
                MediaFormat f = MediaFormat.createVideoFormat("video/avc", w, h);
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(w * h, 512 * 1024));
                codec = MediaCodec.createDecoderByType("video/avc");
                codec.configure(f, surface, null, 0);
                codec.start();
                needKey = true;
                byte[] cfg = hub.configFrame();
                if (cfg != null && cfg != data) queue(cfg);
            }
            if (needKey) {
                if (!HdCameraHub.isKeyFrame(data)) return;
                needKey = false;
            }
            queue(data);
            int out;
            while ((out = codec.dequeueOutputBuffer(info, 0)) >= 0 || out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (out >= 0) codec.releaseOutputBuffer(out, true);
            }
        } catch (Exception e) {
            EventLog.e("decoder", "H.264 decode error, restarting decoder", e);
            release();
        }
    }

    private void queue(byte[] data) {
        int in = codec.dequeueInputBuffer(20000);
        if (in < 0) return;
        ByteBuffer b = codec.getInputBuffer(in);
        if (b == null || data.length > b.capacity()) {
            codec.queueInputBuffer(in, 0, 0, 0, 0);
            return;
        }
        b.clear();
        b.put(data);
        codec.queueInputBuffer(in, 0, data.length, System.nanoTime() / 1000, 0);
    }

    private void release() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
            codec = null;
        }
    }
}
