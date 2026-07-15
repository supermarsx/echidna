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
import com.echidna.lsposed.hooks.AudioRecordHook;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XposedBridge;

/**
 * Fetches target-UID-scoped policy from the companion's explicit read-only Binder component.
 *
 * <p>Every bind attempt owns a fresh listener, connection, and monotonically increasing local
 * epoch. Binder work is dispatched to one serial worker; framework callbacks only publish an
 * immediate fail-closed state and enqueue cleanup. A delayed callback from an obsolete service
 * incarnation can therefore neither restore policy nor acknowledge its drain through a newer
 * provider.
 */
final class ProfileSyncReceiver {

    private static final String TAG = "EchidnaPolicySync";
    private static final long RECONNECT_DELAY_MS = 1000L;
    private static final long CONNECT_TIMEOUT_MS = 5_000L;
    private static final long CAPTURE_HANDOFF_PROVIDER_API_VERSION = 6L;
    private static final long TELEMETRY_PROVIDER_API_VERSION = 4L;
    private static final long TELEMETRY_PROOF_PROVIDER_API_VERSION = 5L;
    private static final int TELEMETRY_VALUE_BYTES = 48;
    private static final int TELEMETRY_PROOF_VALUE_BYTES = 112;
    private static final ComponentName POLICY_COMPONENT = new ComponentName(
            "com.echidna.app",
            "com.echidna.control.service.PolicySnapshotService");

    interface BindingAdapter {
        Context context();
        boolean bind(Context context, Intent intent, ServiceConnection connection);
        void unbind(Context context, ServiceConnection connection);
    }

    interface DrainCompletion {
        void onDrained(long generation, long handoffToken);
    }

    interface CaptureRouteDrainer {
        void request(
                long generation, long handoffToken, DrainCompletion completion);
    }

    private static final class AndroidBindingAdapter implements BindingAdapter {
        @Override
        public Context context() {
            Application application = AndroidAppHelper.currentApplication();
            return application != null ? application.getApplicationContext() : null;
        }

        @Override
        public boolean bind(Context context, Intent intent, ServiceConnection connection) {
            return context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        }

        @Override
        public void unbind(Context context, ServiceConnection connection) {
            context.unbindService(connection);
        }
    }

    private final ProfileSnapshotStore store;
    private final String processName;
    private final BindingAdapter bindingAdapter;
    private final CaptureRouteDrainer captureRouteDrainer;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean rebindScheduled = new AtomicBoolean(false);
    private final AtomicLong bindingEpoch = new AtomicLong();
    private final Object bindingLock = new Object();
    private BindingSession currentBinding;

    private final class BindingSession {
        final long epoch;
        final Context context;
        final AtomicBoolean acceptsCallbacks = new AtomicBoolean(false);
        final AtomicBoolean listenerRegistered = new AtomicBoolean(false);
        final AtomicBoolean cleanupScheduled = new AtomicBoolean(false);
        final AtomicLong latestRevocationToken = new AtomicLong();
        final IEchidnaPolicyListener listener;
        final ServiceConnection connection;
        volatile IEchidnaPolicyProvider provider;
        volatile boolean bindRegistered;

        BindingSession(long epoch, Context context) {
            this.epoch = epoch;
            this.context = context;
            listener = new IEchidnaPolicyListener.Stub() {
                @Override
                public void onPolicyChanged(long generation) {
                    if (generation <= 0L || !isCurrentCallback(BindingSession.this)) {
                        return;
                    }
                    execute(() -> fetchLatest(BindingSession.this));
                }

                @Override
                public void onCaptureOwnerRevoked(long generation, long handoffToken) {
                    if (
                            generation <= 0L || handoffToken <= 0L ||
                            !isCurrentCallback(BindingSession.this) ||
                            !acceptRevocation(BindingSession.this, handoffToken)) {
                        return;
                    }
                    store.failClosed();
                    captureRouteDrainer.request(
                            generation,
                            handoffToken,
                            (drainedGeneration, drainedToken) -> reportCaptureOwnerInactive(
                                    BindingSession.this,
                                    drainedGeneration,
                                    drainedToken));
                }
            };
            connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    handleServiceConnected(BindingSession.this, name, service);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    invalidateBinding(BindingSession.this, true);
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    invalidateBinding(BindingSession.this, true);
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    invalidateBinding(BindingSession.this, true);
                }
            };
        }
    }

    ProfileSyncReceiver(ProfileSnapshotStore store, String processName) {
        this(
                store,
                processName,
                new AndroidBindingAdapter(),
                (generation, handoffToken, completion) ->
                        AudioRecordHook.requestCaptureRouteDrain(
                                generation,
                                handoffToken,
                                completion != null ? completion::onDrained : null),
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "echidna-policy-binder");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    ProfileSyncReceiver(
            ProfileSnapshotStore store,
            String processName,
            BindingAdapter bindingAdapter,
            CaptureRouteDrainer captureRouteDrainer,
            ScheduledExecutorService executor) {
        this.store = store;
        this.processName = processName != null ? processName : "";
        this.bindingAdapter = bindingAdapter;
        this.captureRouteDrainer = captureRouteDrainer;
        this.executor = executor;
    }

    void start() {
        if (started.compareAndSet(false, true)) {
            failClosedAndDrain();
            execute(this::bindExplicitProvider);
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
        return execute(() -> requestLegacyPreprocessorCapabilityOnExecutor(
                audioSessionId, generation, requestNonce, callback));
    }

    boolean reportLegacyPreprocessorTelemetry(
            int audioSessionId, long generation, byte[] capabilityNonce, byte[] snapshot) {
        if (!started.get() || audioSessionId <= 0 || generation <= 0L
                || capabilityNonce == null || capabilityNonce.length != 16
                || snapshot == null
                || (snapshot.length != TELEMETRY_VALUE_BYTES
                    && snapshot.length != TELEMETRY_PROOF_VALUE_BYTES)) {
            return false;
        }
        byte[] ownedNonce = capabilityNonce.clone();
        byte[] ownedSnapshot = snapshot.clone();
        return execute(() -> reportLegacyPreprocessorTelemetryOnExecutor(
                audioSessionId, generation, ownedNonce, ownedSnapshot));
    }

    private void handleServiceConnected(
            BindingSession binding, ComponentName name, IBinder service) {
        if (!POLICY_COMPONENT.equals(name) || service == null) {
            invalidateBinding(binding, true);
            return;
        }
        IEchidnaPolicyProvider connected = IEchidnaPolicyProvider.Stub.asInterface(service);
        synchronized (bindingLock) {
            if (currentBinding != binding) {
                scheduleCleanup(binding, false);
                return;
            }
            binding.provider = connected;
        }
        if (!execute(() -> registerAndFetch(binding))) {
            invalidateBinding(binding, true);
        }
    }

    private void registerAndFetch(BindingSession binding) {
        IEchidnaPolicyProvider connected = binding.provider;
        if (connected == null || !isCurrent(binding)) {
            scheduleCleanup(binding, false);
            return;
        }
        try {
            if (connected.getApiVersion() < CAPTURE_HANDOFF_PROVIDER_API_VERSION) {
                invalidateBinding(binding, true);
                return;
            }
            binding.acceptsCallbacks.set(true);
            boolean registered = connected.registerCaptureOwnerClient(
                    processName,
                    CAPTURE_HANDOFF_PROVIDER_API_VERSION,
                    binding.listener);
            if (!registered) {
                binding.acceptsCallbacks.set(false);
                invalidateBinding(binding, true);
                return;
            }
            binding.listenerRegistered.set(true);
            if (!isCurrent(binding)) {
                scheduleCleanup(binding, false);
                return;
            }
            fetchLatest(binding);
        } catch (RemoteException | RuntimeException error) {
            logFailure("policy provider registration failed", error);
            invalidateBinding(binding, true);
        }
    }

    private void fetchLatest(BindingSession binding) {
        IEchidnaPolicyProvider connected = binding.provider;
        if (connected == null || !isCurrentRegistered(binding)) {
            return;
        }
        try {
            String payload = connected.getPolicySnapshot(processName);
            ProfileSnapshot parsed = ProfileSnapshot.parse(payload);
            if (!isCurrentRegistered(binding)) {
                return;
            }
            if (!parsed.isValid()) {
                failClosedAndDrain();
                return;
            }
            store.update(parsed);
        } catch (RemoteException | RuntimeException error) {
            logFailure("policy fetch failed", error);
            invalidateBinding(binding, true);
        }
    }

    private void reportCaptureOwnerInactive(
            BindingSession binding, long generation, long handoffToken) {
        if (generation <= 0L || handoffToken <= 0L) {
            return;
        }
        execute(() -> {
            IEchidnaPolicyProvider connected = binding.provider;
            if (connected == null || !isCurrentRegistered(binding)) {
                return;
            }
            try {
                connected.reportCaptureOwnerInactive(processName, generation, handoffToken);
            } catch (RemoteException error) {
                if (!connected.asBinder().isBinderAlive()) {
                    invalidateBinding(binding, true);
                }
            } catch (RuntimeException error) {
                logFailure("capture owner drain report failed", error);
            }
        });
    }

    private void reportLegacyPreprocessorTelemetryOnExecutor(
            int audioSessionId, long generation, byte[] capabilityNonce, byte[] snapshot) {
        BindingSession binding = currentRegisteredBinding();
        if (binding == null) {
            return;
        }
        IEchidnaPolicyProvider connected = binding.provider;
        try {
            long apiVersion = connected.getApiVersion();
            if (snapshot.length == TELEMETRY_PROOF_VALUE_BYTES) {
                if (apiVersion < TELEMETRY_PROOF_PROVIDER_API_VERSION) {
                    return;
                }
                connected.reportLegacyPreprocessorTelemetryProofV5(
                        audioSessionId, processName, generation, snapshot);
            } else {
                if (apiVersion < TELEMETRY_PROVIDER_API_VERSION) {
                    return;
                }
                connected.reportLegacyPreprocessorTelemetryV4(
                        audioSessionId, processName, generation, capabilityNonce, snapshot);
            }
        } catch (RemoteException error) {
            if (!connected.asBinder().isBinderAlive()) {
                logFailure("telemetry provider disconnected", error);
                invalidateBinding(binding, true);
            }
        } catch (RuntimeException error) {
            logFailure("telemetry provider report failed", error);
        }
    }

    private void requestLegacyPreprocessorCapabilityOnExecutor(
            int audioSessionId,
            long generation,
            byte[] nonce,
            ProfileSnapshotStore.LegacyCapabilityCallback callback) {
        BindingSession binding = currentRegisteredBinding();
        if (binding == null) {
            callback.onFailure("unavailable");
            return;
        }
        IEchidnaPolicyProvider connected = binding.provider;
        AtomicBoolean delivered = new AtomicBoolean(false);
        try {
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
                            byte[] result = envelope;
                            if (!execute(() -> {
                                if (!isCurrentRegistered(binding)) {
                                    callback.onFailure("stale_binding");
                                    return;
                                }
                                callback.onResult(
                                        status,
                                        callbackGeneration,
                                        result,
                                        diagnostic);
                            })) {
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
                invalidateBinding(binding, true);
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
        if (!started.get()) {
            return;
        }
        Context context = bindingAdapter.context();
        if (context == null) {
            scheduleRebind();
            return;
        }
        BindingSession binding = new BindingSession(nextBindingEpoch(), context);
        synchronized (bindingLock) {
            if (currentBinding != null) {
                return;
            }
            currentBinding = binding;
        }
        Intent intent = new Intent().setComponent(POLICY_COMPONENT);
        try {
            boolean registered = bindingAdapter.bind(context, intent, binding.connection);
            binding.bindRegistered = registered;
            if (!registered) {
                invalidateBinding(binding, true);
                return;
            }
            executor.schedule(() -> {
                if (isCurrent(binding) && binding.provider == null) {
                    invalidateBinding(binding, true);
                }
            }, CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException error) {
            logFailure("explicit policy bind failed", error);
            invalidateBinding(binding, true);
        }
    }

    private long nextBindingEpoch() {
        while (true) {
            long next = bindingEpoch.incrementAndGet();
            if (next > 0L) {
                return next;
            }
            bindingEpoch.compareAndSet(next, 0L);
        }
    }

    private boolean isCurrent(BindingSession binding) {
        synchronized (bindingLock) {
            return currentBinding == binding;
        }
    }

    private boolean isCurrentCallback(BindingSession binding) {
        synchronized (bindingLock) {
            return currentBinding == binding && binding.acceptsCallbacks.get();
        }
    }

    private boolean isCurrentRegistered(BindingSession binding) {
        synchronized (bindingLock) {
            return currentBinding == binding && binding.listenerRegistered.get();
        }
    }

    private boolean acceptRevocation(BindingSession binding, long handoffToken) {
        while (isCurrentCallback(binding)) {
            long current = binding.latestRevocationToken.get();
            if (handoffToken <= current) {
                return false;
            }
            if (binding.latestRevocationToken.compareAndSet(current, handoffToken)) {
                return true;
            }
        }
        return false;
    }

    private BindingSession currentRegisteredBinding() {
        synchronized (bindingLock) {
            BindingSession binding = currentBinding;
            return binding != null && binding.listenerRegistered.get() ? binding : null;
        }
    }

    private void invalidateBinding(BindingSession binding, boolean reconnect) {
        boolean wasCurrent;
        synchronized (bindingLock) {
            wasCurrent = currentBinding == binding;
            if (wasCurrent) {
                currentBinding = null;
            }
            binding.acceptsCallbacks.set(false);
        }
        if (wasCurrent) {
            failClosedAndDrain();
        }
        scheduleCleanup(binding, reconnect && wasCurrent);
    }

    private void failClosedAndDrain() {
        long revokedGeneration = store.getSnapshot().generation();
        store.failClosed();
        if (revokedGeneration > 0L) {
            captureRouteDrainer.request(revokedGeneration, 0L, null);
        }
    }

    private void scheduleCleanup(BindingSession binding, boolean reconnect) {
        if (!binding.cleanupScheduled.compareAndSet(false, true)) {
            if (reconnect) {
                scheduleRebind();
            }
            return;
        }
        if (!execute(() -> {
            IEchidnaPolicyProvider connected = binding.provider;
            binding.acceptsCallbacks.set(false);
            if (connected != null && binding.listenerRegistered.compareAndSet(true, false)) {
                try {
                    connected.unregisterListener(binding.listener);
                } catch (RemoteException | RuntimeException ignored) {
                    // Provider death or an already-unregistered callback is fail-closed.
                }
            }
            if (binding.bindRegistered) {
                binding.bindRegistered = false;
                try {
                    bindingAdapter.unbind(binding.context, binding.connection);
                } catch (IllegalArgumentException ignored) {
                    // The framework may already have released a dead binding.
                } catch (RuntimeException error) {
                    logFailure("explicit policy unbind failed", error);
                }
            }
            if (reconnect) {
                scheduleRebind();
            }
        }) && reconnect) {
            scheduleRebind();
        }
    }

    private void scheduleRebind() {
        if (!started.get() || !rebindScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.schedule(this::bindExplicitProvider, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException error) {
            rebindScheduled.set(false);
        }
    }

    private boolean execute(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException error) {
            return false;
        }
    }

    private static void logFailure(String message, Throwable error) {
        XposedBridge.log(TAG + ": " + message + "; failing closed: "
                + Log.getStackTraceString(error));
    }
}
