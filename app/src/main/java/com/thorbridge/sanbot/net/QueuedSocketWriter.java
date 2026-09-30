package com.thorbridge.sanbot.net;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Writes byte chunks to a socket from its own thread so a slow receiver never blocks the
 * producer (camera / mic callbacks). When the queue is full, chunks are dropped.
 */
public class QueuedSocketWriter {

    public interface OnClosed {
        void closed(QueuedSocketWriter w);
    }

    private final Socket socket;
    private final BlockingQueue<byte[]> queue;
    private final OnClosed onClosed;
    private volatile boolean open = true;
    private volatile long dropped;

    public QueuedSocketWriter(Socket socket, int capacity, String name, OnClosed onClosed) {
        this.socket = socket;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.onClosed = onClosed;
        Thread t = new Thread(this::loop, name);
        t.setDaemon(true);
        t.start();
    }

    public boolean isOpen() { return open; }
    public long dropped() { return dropped; }
    public String remote() { return socket.getInetAddress().getHostAddress(); }

    /** @return false if the chunk was dropped (queue full) or the writer is closed. */
    public boolean offer(byte[] chunk) {
        if (!open) return false;
        boolean ok = queue.offer(chunk);
        if (!ok) dropped++;
        return ok;
    }

    private void loop() {
        try {
            OutputStream out = socket.getOutputStream();
            while (open) {
                byte[] b = queue.take();
                out.write(b);
            }
        } catch (IOException | InterruptedException ignored) {
        } finally {
            close();
        }
    }

    public void close() {
        synchronized (this) {
            if (!open) return;
            open = false;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        queue.clear();
        queue.offer(new byte[0]);
        if (onClosed != null) onClosed.closed(this);
    }
}
