package com.thorbridge.sanbot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Opens the bridge after the robot boots (the Sanbot SDK needs the activity in the foreground). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!App.get().prefs().autoStartOnBoot()) return;
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }
}
