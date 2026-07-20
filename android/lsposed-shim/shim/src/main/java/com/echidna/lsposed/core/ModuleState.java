package com.echidna.lsposed.core;

import android.text.TextUtils;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks per-process configuration resolved via the control service and exposes
 * helper methods consumed by hook implementations.
 */
public final class ModuleState {

    private static final String TAG = "EchidnaModuleState";

    interface NativeController {
        boolean initialize();
        boolean isEngineReady();
        void setBypass(boolean bypass);
        void setProfile(String profile);
        EchidnaStatus getStatus();
    }

    public interface LegacyCapabilityCallback {
        void onResult(int status, long generation, byte[] envelope, String diagnostic);

        void onFailure(String diagnostic);
    }

    public static final class LegacyPreprocessorPolicy {
        public final boolean eligible;
        public final long generation;

        LegacyPreprocessorPolicy(boolean eligible, long generation) {
            this.eligible = eligible;
            this.generation = generation;
        }
    }

    private static final class Holder {
        private static final ModuleState INSTANCE = new ModuleState(
                ProfileSnapshotStore.getInstance(),
                new NativeController() {
                    @Override
                    public boolean initialize() {
                        return NativeBridge.initialize();
                    }

                    @Override
                    public boolean isEngineReady() {
                        return NativeBridge.isEngineReady();
                    }

                    @Override
                    public void setBypass(boolean bypass) {
                        NativeBridge.setBypass(bypass);
                    }

                    @Override
                    public void setProfile(String profile) {
                        NativeBridge.setProfile(profile);
                    }

                    @Override
                    public EchidnaStatus getStatus() {
                        return NativeBridge.getStatus();
                    }
                },
                true);
    }

    private final ProfileSnapshotStore snapshotStore;
    private final NativeController nativeController;
    private final AtomicReference<AppConfig> currentConfig = new AtomicReference<>(AppConfig.disabled());
    private final AtomicReference<String> currentPackage = new AtomicReference<>("");
    private final AtomicReference<String> currentProcess = new AtomicReference<>("");
    private final AtomicBoolean bypassOverride = new AtomicBoolean(false);
    private final AtomicBoolean hooksActivated = new AtomicBoolean(false);
    private final AtomicInteger lastFramesProcessed = new AtomicInteger(0);
    private final AtomicLong appliedSnapshotVersion = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong policyEpoch = new AtomicLong(0L);
    private final Object policyRefreshLock = new Object();
    private final boolean startReceiver;

    ModuleState(
            ProfileSnapshotStore snapshotStore,
            NativeController nativeController,
            boolean startReceiver) {
        this.snapshotStore = snapshotStore;
        this.nativeController = nativeController;
        this.startReceiver = startReceiver;
        safeNativeInitialise();
        // The explicit Binder receiver starts once the real target process identity is known.
    }

    private AppConfig resolveConfig(String packageName, String processName) {
        try {
            return AppConfig.fromSnapshot(snapshotStore.getSnapshot(), packageName, processName);
        } catch (Throwable throwable) {
            ShimLog.log(
                    TAG + ": policy resolution failed; failing closed: "
                            + Log.getStackTraceString(throwable));
            return AppConfig.disabled();
        }
    }

    public static ModuleState getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * Resolves configuration for the target package/process and returns whether the
     * AudioRecord hooks should be installed.
     */
    public boolean shouldInitializeFor(String packageName, String processName) {
        onProcessAttached(packageName, processName);
        return hooksActivated.get();
    }

    /**
     * Called after the process has been prepared for hooking. This may refresh the configuration
     * when the cached TTL has expired.
     */
    public void onProcessAttached(String packageName, String processName) {
        currentPackage.set(packageName != null ? packageName : "");
        currentProcess.set(processName != null ? processName : "");
        if (startReceiver) {
            try {
                snapshotStore.ensureStarted(packageName, processName);
            } catch (Throwable throwable) {
                ShimLog.log(
                        TAG + ": policy snapshot receiver unavailable; failing closed: "
                                + Log.getStackTraceString(throwable));
            }
        }
        refreshPolicyIfNeeded(true);
    }

    /**
     * Returns whether the hooks should process captured audio blocks.
     */
    public boolean shouldProcessAudio() {
        long permit = beginAudioProcessing();
        return permit != INVALID_AUDIO_PROCESSING_PERMIT
                && isAudioProcessingPermitCurrent(permit);
    }

    /**
     * Returns whether this process is explicitly allowed to hook AudioRecord.
     */
    public boolean isHookAllowed() {
        refreshPolicyIfNeeded(false);
        return hooksActivated.get();
    }

    public boolean isBypassActive() {
        return bypassOverride.get() || currentConfig.get().bypassRequested();
    }

    public void setBypassOverride(boolean enabled) {
        if (bypassOverride.getAndSet(enabled) != enabled) {
            policyEpoch.incrementAndGet();
        }
        nativeController.setBypass(isBypassActive());
    }

    public LegacyPreprocessorPolicy legacyPreprocessorPolicy() {
        refreshPolicyIfNeeded(false);
        long generation = snapshotStore.getSnapshot().generation();
        return new LegacyPreprocessorPolicy(
                generation > 0L && hooksActivated.get() && !isBypassActive(), generation);
    }

    public boolean requestLegacyPreprocessorCapability(
            int audioSessionId,
            long generation,
            byte[] nonce,
            LegacyCapabilityCallback callback) {
        if (callback == null) {
            return false;
        }
        return snapshotStore.requestLegacyPreprocessorCapability(
                audioSessionId,
                generation,
                nonce,
                new ProfileSnapshotStore.LegacyCapabilityCallback() {
                    @Override
                    public void onResult(
                            int status,
                            long callbackGeneration,
                            byte[] envelope,
                            String diagnostic) {
                        callback.onResult(status, callbackGeneration, envelope, diagnostic);
                    }

                    @Override
                    public void onFailure(String diagnostic) {
                        callback.onFailure(diagnostic);
                    }
                });
    }

    public boolean reportLegacyPreprocessorTelemetry(
            int audioSessionId, long generation, byte[] capabilityNonce, byte[] snapshot) {
        return snapshotStore.reportLegacyPreprocessorTelemetry(
                audioSessionId, generation, capabilityNonce, snapshot);
    }

    public void invalidateAudioProcessingPermits() {
        policyEpoch.incrementAndGet();
    }

    public static final long INVALID_AUDIO_PROCESSING_PERMIT = -1L;

    /** Captures a generation token for one transactional AudioRecord post-processing callback. */
    public long beginAudioProcessing() {
        refreshPolicyIfNeeded(false);
        if (!hooksActivated.get() || isBypassActive() || !nativeController.isEngineReady()) {
            return INVALID_AUDIO_PROCESSING_PERMIT;
        }
        return policyEpoch.get();
    }

    /**
     * Revalidates an in-flight callback immediately before committing its transformed bytes.
     * Snapshot revocation or a bypass override invalidates the token and forces rollback.
     */
    public boolean isAudioProcessingPermitCurrent(long permit) {
        if (permit == INVALID_AUDIO_PROCESSING_PERMIT) {
            return false;
        }
        refreshPolicyIfNeeded(false);
        return policyEpoch.get() == permit && hooksActivated.get() && !isBypassActive();
    }

    public void noteProcessingSuccess(int frames) {
        lastFramesProcessed.set(Math.max(frames, 0));
    }

    public int lastFramesProcessed() {
        return lastFramesProcessed.get();
    }

    public EchidnaStatus getNativeStatus() {
        return nativeController.getStatus();
    }

    public String getActiveProfile() {
        AppConfig config = currentConfig.get();
        return config != null ? config.profile() : "";
    }

    public boolean refreshConfiguration() {
        String pkg = currentPackage.get();
        if (TextUtils.isEmpty(pkg)) {
            return false;
        }
        refreshPolicyIfNeeded(true);
        return hooksActivated.get();
    }

    /**
     * Refreshes process policy when the receiver publishes a new snapshot generation. The hot
     * callback path normally performs only atomic reads; JSON resolution is serialized and happens
     * only after a generation change or TTL expiry.
     */
    private void refreshPolicyIfNeeded(boolean force) {
        String pkg = currentPackage.get();
        if (TextUtils.isEmpty(pkg)) {
            return;
        }
        long observedVersion = snapshotStore.version();
        AppConfig observedConfig = currentConfig.get();
        if (!force
                && appliedSnapshotVersion.get() == observedVersion
                && !observedConfig.isExpired()) {
            return;
        }
        synchronized (policyRefreshLock) {
            observedVersion = snapshotStore.version();
            observedConfig = currentConfig.get();
            if (!force
                    && appliedSnapshotVersion.get() == observedVersion
                    && !observedConfig.isExpired()) {
                return;
            }

            String process = currentProcess.get();
            AppConfig resolved = resolveConfig(pkg, process);
            currentConfig.set(resolved);
            logPolicyDecision(pkg, process, resolved);
            hooksActivated.set(resolved.shouldHook(pkg, process));
            applyProfile(resolved.profile());
            policyEpoch.incrementAndGet();
            nativeController.setBypass(isBypassActive());
            // update() publishes the snapshot before incrementing its version. If another update
            // races this resolution, retaining the older generation forces one safe extra refresh.
            appliedSnapshotVersion.set(observedVersion);
        }
    }

    /** Reason last written to the log, so a TTL refresh that changes nothing stays quiet. */
    private final AtomicReference<AppConfig.InertReason> loggedReason = new AtomicReference<>(null);

    /**
     * The reason this process is currently inert, or {@link AppConfig.InertReason#NONE} when hooks
     * are permitted. Never widens policy; it only explains the decision already taken.
     */
    public AppConfig.InertReason inertReason() {
        refreshPolicyIfNeeded(false);
        return currentConfig.get().inertReason();
    }

    /** Emits the resolved decision once per change, so `logcat` explains a silently inert module. */
    private void logPolicyDecision(String packageName, String processName, AppConfig resolved) {
        AppConfig.InertReason reason = resolved.inertReason();
        if (loggedReason.getAndSet(reason) == reason) {
            return;
        }
        ShimLog.log(
                TAG + ": " + packageName + "/" + processName + " -> "
                        + (reason == AppConfig.InertReason.NONE ? "ACTIVE" : "INERT")
                        + " [" + reason.name() + "] " + resolved.inertDescription());
    }

    private void applyProfile(String profile) {
        if (!TextUtils.isEmpty(profile)) {
            nativeController.setProfile(profile);
        }
    }

    private void safeNativeInitialise() {
        try {
            nativeController.initialize();
        } catch (Throwable throwable) {
            ShimLog.log(
                    TAG + ": native initialization failed; failing closed: "
                            + Log.getStackTraceString(throwable));
        }
    }
}
