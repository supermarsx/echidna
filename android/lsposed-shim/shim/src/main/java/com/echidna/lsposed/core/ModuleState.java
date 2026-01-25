package com.echidna.lsposed.core;

import android.text.TextUtils;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks per-process configuration resolved via the control service and exposes
 * helper methods consumed by hook implementations.
 */
public final class ModuleState {

    private static final ModuleState INSTANCE = new ModuleState();

    private final ControlServiceClient controlClient = new ControlServiceClient();
    private final AtomicReference<AppConfig> currentConfig = new AtomicReference<>(AppConfig.disabled());
    private final AtomicReference<String> currentPackage = new AtomicReference<>("");
    private final AtomicReference<String> currentProcess = new AtomicReference<>("");
    private final AtomicBoolean bypassOverride = new AtomicBoolean(false);
    private final AtomicBoolean hooksActivated = new AtomicBoolean(false);
    private final AtomicInteger lastFramesProcessed = new AtomicInteger(0);

    private ModuleState() {
        NativeBridge.initialize();
    }

    public static ModuleState getInstance() {
        return INSTANCE;
    }

    /**
     * Resolves configuration for the target package/process and returns whether the
     * AudioRecord hooks should be installed.
     */
    public boolean shouldInitializeFor(String packageName, String processName) {
        AppConfig config = controlClient.getConfiguration(packageName, processName);
        currentPackage.set(packageName);
        currentProcess.set(processName);
        currentConfig.set(config);
        hooksActivated.set(config.shouldHook(packageName, processName));
        applyProfile(config.profile());
        NativeBridge.setBypass(isBypassActive());
        return hooksActivated.get();
    }

    /**
     * Called after the process has been prepared for hooking. This may refresh the configuration
     * when the cached TTL has expired.
     */
    public void onProcessAttached(String packageName, String processName) {
        AppConfig config = currentConfig.get();
        if (config.isExpired()) {
            config = controlClient.forceRefresh(packageName, processName);
            currentConfig.set(config);
            hooksActivated.set(config.shouldHook(packageName, processName));
            applyProfile(config.profile());
        }
        NativeBridge.setBypass(isBypassActive());
    }

    /**
     * Returns whether the hooks should process captured audio blocks.
     */
    public boolean shouldProcessAudio() {
        return hooksActivated.get() && !isBypassActive() && NativeBridge.isEngineReady();
    }

    /**
     * Returns whether this process is explicitly allowed to hook AudioRecord.
     */
    public boolean isHookAllowed() {
        return hooksActivated.get();
    }

    public boolean isBypassActive() {
        return bypassOverride.get() || currentConfig.get().bypassRequested();
    }

    public void setBypassOverride(boolean enabled) {
        bypassOverride.set(enabled);
        NativeBridge.setBypass(isBypassActive());
    }

    public void noteProcessingSuccess(int frames) {
        lastFramesProcessed.set(Math.max(frames, 0));
    }

    public int lastFramesProcessed() {
        return lastFramesProcessed.get();
    }

    public EchidnaStatus getNativeStatus() {
        return NativeBridge.getStatus();
    }

    public String getActiveProfile() {
        AppConfig config = currentConfig.get();
        return config != null ? config.profile() : "";
    }

    public boolean refreshConfiguration() {
        String pkg = currentPackage.get();
        String proc = currentProcess.get();
        if (TextUtils.isEmpty(pkg)) {
            return false;
        }
        AppConfig config = controlClient.forceRefresh(pkg, proc);
        currentConfig.set(config);
        hooksActivated.set(config.shouldHook(pkg, proc));
        applyProfile(config.profile());
        NativeBridge.setBypass(isBypassActive());
        return hooksActivated.get();
    }

    private void applyProfile(String profile) {
        if (!TextUtils.isEmpty(profile)) {
            NativeBridge.setProfile(profile);
        }
    }
}
