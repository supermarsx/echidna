package com.echidna.control.service;

import com.echidna.control.service.IEchidnaTelemetryListener;

// Single canonical control contract. Compiled once by the :service library and
// consumed by the companion app (which no longer ships a divergent copy).
interface IEchidnaControlService {
    void installModule(String archivePath);
    void uninstallModule();
    // Unload-first support: a live Zygisk module cannot be hot-unloaded, so it must be
    // disabled (Zygisk stops loading it on the next boot) before the module op + reboot.
    // Writes the Magisk 'disable' marker (/data/adb/modules/echidna/disable); returns true
    // only when the marker is confirmed present. Fail-closed so callers can abort honestly.
    boolean disableModule();
    // Best-effort privileged reboot to complete the load/unload. Returns true if dispatched.
    boolean rebootDevice();
    // Forces a privileged status refresh and returns the fresh combined status
    // JSON (module + SELinux + audio-stack). See getModuleStatus for the schema.
    String refreshStatus();
    // Combined module/SELinux/HAL status JSON (never fabricated app-side).
    String getModuleStatus();
    void updateWhitelist(String processName, boolean enabled);
    void pushProfile(String profileId, String profileJson);
    String[] listProfiles();
    // Read-back of persisted per-app policy: {"whitelist":{proc:bool},"appBindings":{pkg:presetId}}.
    String getWhitelistBindings();
    String getTelemetrySnapshot();
    boolean isTelemetryOptedIn();
    void registerTelemetryListener(IEchidnaTelemetryListener listener);
    void unregisterTelemetryListener(IEchidnaTelemetryListener listener);
    void setTelemetryOptIn(boolean enabled);
    String exportTelemetry(boolean includeTrends);
    String exportDiagnostics(boolean includeTrends);
    void setProfile(String profile);
    void pushProfileSnapshot(String profileId, String profileJson);
    void setLatencyModeOverride(String profileId, String latencyMode);
    void setAppPresetBinding(String packageName, String presetId);
    // Atomically replaces the complete app-owned v2 policy. The service owns and persists the
    // monotonic generation used by native/shim readers.
    boolean synchronizePolicyState(String stateJson);
    // Global engine controls; each mutation is persisted and pushed via the
    // ProfileSyncBridge snapshot ("control" object) for the native/shim path.
    void setMasterEnabled(boolean enabled);
    void setBypass(boolean bypass);
    void triggerPanic(long holdMs);
    void setSidetone(boolean enabled, float gainDb);
    void setEngineMode(String engineMode);
    // Read-back of the global control state JSON (masterEnabled/bypass/etc).
    String getControlState();
    int getStatus();
    int processBlock(in float[] input, inout float[] output, int frames, int sampleRate, int channelCount);
    long getApiVersion();
    // Companion-owned fail-closed gate. Stored separately from strict v2 policy JSON.
    boolean isLegacyPreprocessorEnabled();
    boolean setLegacyPreprocessorEnabled(boolean enabled);
}
