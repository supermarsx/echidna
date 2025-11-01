package com.echidna.lsposed.core;

import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;

/**
 * Reflection-based helper that retrieves system services without depending on hidden APIs.
 */
final class ServiceLocator {

    private static final String TAG = "EchidnaSvcLocator";

    private static final Method GET_SERVICE;

    static {
        Method method = null;
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            method = serviceManager.getDeclaredMethod("getService", String.class);
            method.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            XposedBridge.log(TAG + ": unable to resolve ServiceManager#getService: " + Log.getStackTraceString(e));
        }
        GET_SERVICE = method;
    }

    private ServiceLocator() {
    }

    static IBinder getService(String name) {
        if (GET_SERVICE == null) {
            return null;
        }
        try {
            return (IBinder) GET_SERVICE.invoke(null, name);
        } catch (ReflectiveOperationException e) {
            XposedBridge.log(TAG + ": failed to obtain service " + name + ": " + Log.getStackTraceString(e));
            return null;
        }
    }
}
