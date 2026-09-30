package com.thorbridge.sanbot.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.thorbridge.sanbot.App;
import com.thorbridge.sanbot.MainActivity;
import com.thorbridge.sanbot.R;

/** Keeps the network bridge alive (foreground service + partial wake lock). */
public class BridgeService extends Service {

    private static final int NOTIFICATION_ID = 1;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context ctx) {
        ctx.startService(new Intent(ctx, BridgeService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ThorBridge:bridge");
        wakeLock.acquire();
        startForeground(NOTIFICATION_ID, buildNotification());
        App.get().bridge().start();
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("bridge", "Thor bridge", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "bridge");
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle(getString(R.string.app_name))
                .setContentText("Bridge running")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        App.get().bridge().stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
