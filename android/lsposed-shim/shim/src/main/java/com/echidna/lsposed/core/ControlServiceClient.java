package com.echidna.lsposed.core;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * Lightweight binder client that queries the Echidna control service for per-app configuration.
 */
final class ControlServiceClient {

    private static final String TAG = "EchidnaCtlClient";
    private static final String SERVICE_NAME = "echidna_control";
    private static final String INTERFACE_DESCRIPTOR = "com.echidna.control.IControlService";
    private static final int TRANSACTION_GET_APP_CONFIG = IBinder.FIRST_CALL_TRANSACTION + 1;

    private final Map<String, CachedEntry> cache = new ConcurrentHashMap<>();

    AppConfig getConfiguration(String packageName, String processName) {
        String key = cacheKey(packageName, processName);
        CachedEntry cached = cache.get(key);
        if (cached != null && !cached.config.isExpired()) {
            return cached.config;
        }
        AppConfig config = queryService(packageName, processName);
        cache.put(key, new CachedEntry(config));
        return config;
    }

    AppConfig forceRefresh(String packageName, String processName) {
        AppConfig config = queryService(packageName, processName);
        cache.put(cacheKey(packageName, processName), new CachedEntry(config));
        return config;
    }

    private AppConfig queryService(String packageName, String processName) {
        IBinder binder = ServiceLocator.getService(SERVICE_NAME);
        if (binder == null) {
            return AppConfig.disabled();
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INTERFACE_DESCRIPTOR);
            data.writeString(packageName);
            data.writeString(processName);
            boolean success = binder.transact(TRANSACTION_GET_APP_CONFIG, data, reply, 0);
            if (!success) {
                return AppConfig.disabled();
            }
            reply.readException();
            Bundle bundle = reply.readBundle(AppConfig.class.getClassLoader());
            if (bundle == null) {
                return AppConfig.disabled();
            }
            return AppConfig.fromBundle(bundle);
        } catch (RemoteException e) {
            XposedBridge.log(TAG + ": remote error fetching config for " + packageName + ": " + Log.getStackTraceString(e));
            return AppConfig.disabled();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static String cacheKey(String packageName, String processName) {
        String process = TextUtils.isEmpty(processName) ? "_" : processName;
        return packageName + '#' + process;
    }

    private static final class CachedEntry {
        final AppConfig config;

        CachedEntry(AppConfig config) {
            this.config = config;
        }
    }
}
