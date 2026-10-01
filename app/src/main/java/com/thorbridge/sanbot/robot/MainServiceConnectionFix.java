package com.thorbridge.sanbot.robot;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.qihancloud.opensdk.base.BindBaseActivity;
import com.qihancloud.opensdk.beans.OperationResult;
import com.sunbo.main.aidl.IMyServiceCallback;
import com.thorbridge.sanbot.EventLog;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * SDK 1.1.8 calls setCurrentStatus(..).getResult() on connect, but MainService 1.5.x returns null there,
 * so the SDK crashes and skips the rest of its connect sequence. This wrapper finishes that sequence.
 */
public final class MainServiceConnectionFix implements ServiceConnection {

    private static final String TAG = "robot";

    private final BindBaseActivity act;
    private final ServiceConnection sdk;
    private final Runnable onConnected;

    public MainServiceConnectionFix(BindBaseActivity act, ServiceConnection sdk, Runnable onConnected) {
        this.act = act;
        this.sdk = sdk;
        this.onConnected = onConnected;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        try {
            sdk.onServiceConnected(name, binder);
        } catch (NullPointerException e) {
            StackTraceElement[] st = e.getStackTrace();
            if (st.length == 0 || !st[0].getClassName().equals(sdk.getClass().getName()) || act.mMainService == null) throw e;
            EventLog.w(TAG, "MainService returned no status result (old firmware); completing SDK connect manually");
            finishConnect();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        sdk.onServiceDisconnected(name);
    }

    /** Same steps as BindBaseActivity$MusicConnection.onServiceConnected after the failing call. */
    @SuppressWarnings("unchecked")
    private void finishConnect() {
        try {
            call("setDefaultMetaData");
            call("loadApplicationMetaData", 1);
            call("loadActivityMetaData", 1);
            ((Map<Integer, Integer>) field("statusMap").get(act)).put(1007, 118);
            call("sendAppStatus");
            Method send = BindBaseActivity.class.getDeclaredMethod("sendCommandToMainService", int.class, int.class, String.class);
            send.setAccessible(true);
            OperationResult r = (OperationResult) send.invoke(act, 293, 0, "");
            if (r != null) field("mainserviceVersion").set(act, r.getResult());
        } catch (Exception e) {
            EventLog.e(TAG, "SDK connect: applying app settings failed", unwrap(e));
        }
        onConnected.run();
        try {
            act.mMainService.registerCallback((IMyServiceCallback) field("sCallback").get(act));
        } catch (Exception e) {
            EventLog.e(TAG, "SDK connect: registering the sensor/speech callback failed", unwrap(e));
        }
    }

    private void call(String method, int arg) throws Exception {
        Method m = BindBaseActivity.class.getDeclaredMethod(method, int.class);
        m.setAccessible(true);
        m.invoke(act, arg);
    }

    private void call(String method) throws Exception {
        Method m = BindBaseActivity.class.getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(act);
    }

    private static Field field(String name) throws Exception {
        Field f = BindBaseActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Throwable unwrap(Exception e) {
        return e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
    }
}
