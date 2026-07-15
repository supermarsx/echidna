package com.echidna.lsposed.core;

import android.app.Application;
import android.app.AndroidAppHelper;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.echidna.control.service.IEchidnaCapabilityCallback;
import com.echidna.control.service.IEchidnaPolicyListener;
import com.echidna.control.service.IEchidnaPolicyProvider;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;

/**
 * Fetches target-UID-scoped policy from the companion's explicit read-only Binder component.
 *
 * <p>The former abstract-socket client could not authenticate which UID had won the global socket
 * name. Explicit component binding delegates server identity to Android's package/component
 * resolver; the provider then authenticates this injected process through Binder's caller UID.
 */
final class ProfileSyncReceiver {

    private static final String TAG = "EchidnaPolicySync";
    private static final long RECONNECT_DELAY_MS = 1000L;
    private static final long CAPABILITY_PROVIDER_API_VERSION = 2L;
    private static final ComponentName POLICY_COMPONENT = new ComponentName(
            "com.echidna.app",
            "com.echidna.control.service.PolicySnapshotService");

    private final ProfileSnapshotStore store;
    private final String processName;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "echidna-policy-binder");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean bindPending = new AtomicBoolean(false);
    private final AtomicBoolean rebindScheduled = new AtomicBoolean(false);
    private volatile Context context;
    private volatile IEchidnaPolicyProvider provider;
    private volatile boolean bound;
    private volatile boolean bindingRegistered;

    private final IEchidnaPolicyListener listener = new IEchidnaPolicyListener.Stub() {
        @Override
        public void onPolicyChanged(long generation) {
            executor.execute(ProfileSyncReceiver.this::fetchLatest);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            bindPending.set(false);
            if (!POLICY_COMPONENT.equals(name) || service == null) {
                failClosedAndReconnect();
                return;
            }
            bound = true;
            provider = IEchidnaPolicyProvider.Stub.asInterface(service);
            executor.execute(() -> {
                IEchidnaPolicyProvider connected = provider;
                if (connected == null) {
                    failClosedAndReconnect();
                    return;
                }
                try {
                    // Register before the first fetch so an intervening generation is not missed.
                    connected.registerListener(processName, listener);
                    fetchLatest();
                } catch (RemoteException | RuntimeException error) {
                    logFailure("policy provider registration failed", error);
                    failClosedAndReconnect();
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            failClosedAndReconnect();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            failClosedAndReconnect();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            failClosedAndReconnect();
        }
    };

    ProfileSyncReceiver(ProfileSnapshotStore store, String processName) {
        this.store = store;
        this.processName = processName != null ? processName : "";
    }

    void start() {
        if (started.compareAndSet(false, true)) {
            executor.execute(this::bindExplicitProvider);
        }
    }

    boolean requestLegacyPreprocessorCapability(
            int audioSessionId,
            long generation,
            byte[] nonce,
            ProfileSnapshotStore.LegacyCapabilityCallback callback) {
        if (!started.get() || audioSessionId <= 0 || generation <= 0L
                || nonce == null || nonce.length != 16 || callback == null) {
            return false;
        }
        byte[] requestNonce = nonce.clone();
        try {
            executor.execute(() -> requestLegacyPreprocessorCapabilityOnExecutor(
                    audioSessionId, generation, requestNonce, callback));
            return true;
        } catch (RejectedExecutionException error) {
            logFailure("capability request executor rejected work", error);
            return false;
        }
    }

    private void requestLegacyPreprocessorCapabilityOnExecutor(
            int audioSessionId,
            long generation,
            byte[] nonce,
            ProfileSnapshotStore.LegacyCapabilityCallback callback) {
        IEchidnaPolicyProvider connected = provider;
        if (connected == null) {
            callback.onFailure("unavailable");
            return;
        }
        AtomicBoolean delivered = new AtomicBoolean(false);
        try {
            if (connected.getApiVersion() < CAPABILITY_PROVIDER_API_VERSION) {
                callback.onFailure("unsupported");
                return;
            }
            connected.requestLegacyPreprocessorCapability(
                    audioSessionId,
                    processName,
                    generation,
                    nonce,
                    new IEchidnaCapabilityCallback.Stub() {
                        @Override
                        public void onCapabilityResult(
                                int status,
                                long callbackGeneration,
                                byte[] envelope,
                                String diagnostic) {
                            if (!delivered.compareAndSet(false, true)) {
                                return;
                            }
                            // AIDL unmarshals a process-private byte array. Re-dispatch the owned
                            // instance without copying a bounded payload on the Binder thread.
                            byte[] result = envelope;
                            try {
                                executor.execute(() -> callback.onResult(
                                        status,
                                        callbackGeneration,
                                        result,
                                        diagnostic));
                            } catch (RejectedExecutionException error) {
                                callback.onFailure("callback_executor_rejected");
                            }
                        }
                    });
        } catch (RemoteException error) {
            if (delivered.compareAndSet(false, true)) {
                callback.onFailure(connected.asBinder().isBinderAlive()
                        ? "unsupported"
                        : "disconnected");
            }
            if (!connected.asBinder().isBinderAlive()) {
                logFailure("capability provider disconnected", error);
                failClosedAndReconnect();
            }
        } catch (RuntimeException error) {
            if (delivered.compareAndSet(false, true)) {
                callback.onFailure("request_failed");
            }
            logFailure("capability provider request failed", error);
        }
    }

    private void bindExplicitProvider() {
        rebindScheduled.set(false);
        if (bound || !bindPending.compareAndSet(false, true)) {
            return;
        }
        Application application = AndroidAppHelper.currentApplication();
        if (application == null) {
            bindPending.set(false);
            scheduleRebind();
            return;
        }
        Context appContext = application.getApplicationContext();
        context = appContext;
        Intent intent = new Intent().setComponent(POLICY_COMPONENT);
        try {
            bindingRegistered = true;
            if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                bindingRegistered = false;
                bindPending.set(false);
                scheduleRebind();
            }
        } catch (RuntimeException error) {
            bindingRegistered = false;
            bindPending.set(false);
            logFailure("explicit policy bind failed", error);
            scheduleRebind();
        }
    }

    private void fetchLatest() {
        IEchidnaPolicyProvider connected = provider;
        if (connected == null) {
            store.failClosed();
            return;
        }
        try {
            String payload = connected.getPolicySnapshot(processName);
            ProfileSnapshot parsed = ProfileSnapshot.parse(payload);
            if (!parsed.isValid()) {
                store.failClosed();
                return;
            }
            store.update(parsed);
        } catch (RemoteException | RuntimeException error) {
            logFailure("policy fetch failed", error);
            failClosedAndReconnect();
        }
    }

    private void failClosedAndReconnect() {
        provider = null;
        store.failClosed();
        Context appContext = context;
        if (bindingRegistered && appContext != null) {
            try {
                appContext.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // The framework may already have released a dead binding.
            }
        }
        bindingRegistered = false;
        bound = false;
        bindPending.set(false);
        scheduleRebind();
    }

    private void scheduleRebind() {
        if (!started.get() || !rebindScheduled.compareAndSet(false, true)) {
            return;
        }
        executor.schedule(this::bindExplicitProvider, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void logFailure(String message, Throwable error) {
        XposedBridge.log(TAG + ": " + message + "; failing closed: "
                + Log.getStackTraceString(error));
    }
}
