package com.echidna.lsposed.core;

import android.os.SystemClock;

/**
 * Per-process policy decision derived from the ProfileSyncBridge snapshot
 * (t2-e6 signatures &sect;5).
 *
 * <p>Fail-closed by construction: {@link #disabled()} denies hooking and forces
 * bypass, and {@link #fromSnapshot} only enables hooking when the engine is
 * globally enabled AND the process (or its package) is explicitly whitelisted.
 */
public final class AppConfig {

    private static final long DEFAULT_TTL_MS = 30_000L;

    private final boolean hookingEnabled;
    private final boolean bypassRequested;
    private final String profile;
    private final long expiresAtMs;

    private AppConfig(boolean hookingEnabled, boolean bypassRequested, String profile, long expiresAtMs) {
        this.hookingEnabled = hookingEnabled;
        this.bypassRequested = bypassRequested;
        this.profile = profile != null ? profile : "";
        this.expiresAtMs = expiresAtMs;
    }

    /** Fail-closed default used whenever policy is unknown or unavailable. */
    public static AppConfig disabled() {
        return new AppConfig(false, true, "", 0L);
    }

    /**
     * Resolves policy for a package/process from the shared snapshot. Hooking is
     * enabled only when the engine is globally enabled AND the process (or its
     * package) is explicitly whitelisted; anything else is fail-closed. Bypass is
     * requested whenever the engine is globally off (master disabled / bypassed /
     * panic hold), which drives the native bypass flag.
     */
    public static AppConfig fromSnapshot(ProfileSnapshot snapshot, String packageName, String processName) {
        if (snapshot == null) {
            return disabled();
        }
        boolean globallyEnabled = snapshot.isGloballyEnabled();
        boolean processAllowed = snapshot.isProcessAllowed(packageName, processName);
        boolean ownedByLsposed = "lsposed".equals(
                snapshot.captureOwner(packageName, processName));
        String profile = globallyEnabled && processAllowed && ownedByLsposed
                ? snapshot.resolveProfile(packageName)
                : "";
        // The snapshot contract requires both explicit process policy and a resolvable
        // per-package preset. Never run with a stale native profile after a binding was deleted.
        boolean allowed = globallyEnabled && processAllowed && ownedByLsposed && !profile.isEmpty();
        long expiresAt = SystemClock.elapsedRealtime() + DEFAULT_TTL_MS;
        return new AppConfig(allowed, !allowed, allowed ? profile : "", expiresAt);
    }

    public boolean shouldHook(String packageName, String processName) {
        return hookingEnabled;
    }

    public boolean bypassRequested() {
        return bypassRequested;
    }

    public String profile() {
        return profile;
    }

    public boolean isExpired() {
        return expiresAtMs > 0 && SystemClock.elapsedRealtime() > expiresAtMs;
    }
}
