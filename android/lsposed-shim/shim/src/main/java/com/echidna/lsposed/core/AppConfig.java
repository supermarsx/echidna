package com.echidna.lsposed.core;

import android.os.SystemClock;

/**
 * Per-process policy decision derived from the ProfileSyncBridge snapshot
 * (t2-e6 signatures &sect;5).
 *
 * <p>Fail-closed by construction: {@link #disabled()} denies hooking and forces
 * bypass, and {@link #fromSnapshot} only enables hooking when the engine is
 * globally enabled AND the process (or its package) is explicitly whitelisted.
 *
 * <p>Every denial also carries an {@link InertReason}. The module installs hooks on every scoped
 * process before policy can arrive, so "loaded and active in LSPosed but transforming nothing" is a
 * normal, expected state; without a recorded reason it is indistinguishable from a broken install.
 * The reason is diagnostic only — it never widens what is allowed.
 */
public final class AppConfig {

    private static final long DEFAULT_TTL_MS = 30_000L;

    /** Why this process is loaded but inert. Ordered from most to least fundamental. */
    public enum InertReason {
        /** Hooking is permitted; the process is not inert. */
        NONE("hooking is active"),

        /**
         * No valid policy snapshot has ever been applied in this process: the companion has not
         * published one, the Binder policy channel never bound, or every payload failed validation.
         */
        POLICY_UNAVAILABLE(
                "no valid policy snapshot has reached this process (policy binding never "
                        + "established). Open the Echidna companion so it can publish policy."),

        /** Master switch off, bypass engaged, or an active panic hold. */
        ENGINE_DISABLED(
                "the engine is globally disabled, bypassed, or inside a panic hold. Re-enable "
                        + "master processing in the Echidna companion."),

        /** The process (and its base package) is absent from, or disabled in, the whitelist. */
        NOT_WHITELISTED(
                "this process is not enabled in the companion's Per-App Whitelist. Enable it "
                        + "under Settings -> Per-App Whitelist."),

        /**
         * Policy assigns capture of this process to the native Zygisk engine (or to nobody), so the
         * shim must stay out of the audio path to avoid two owners transforming the same buffers.
         */
        NOT_CAPTURE_OWNER(
                "the capture owner for this process is not \"lsposed\". The companion only assigns "
                        + "the LSPosed shim as capture owner while DSP engine mode is Compatibility "
                        + "-- set Settings -> Engine -> DSP engine mode -> Compatibility."),

        /** Admitted by policy, but no preset payload could be resolved for the package. */
        NO_PROFILE(
                "no preset could be resolved for this package. Assign a preset to it in the "
                        + "companion's Per-App Whitelist.");

        private final String description;

        InertReason(String description) {
            this.description = description;
        }

        /** Human-readable explanation, phrased so a user can act on it. */
        public String description() {
            return description;
        }
    }

    private final boolean hookingEnabled;
    private final boolean bypassRequested;
    private final String profile;
    private final long expiresAtMs;
    private final InertReason inertReason;
    private final String observedCaptureOwner;

    private AppConfig(
            boolean hookingEnabled,
            boolean bypassRequested,
            String profile,
            long expiresAtMs,
            InertReason inertReason,
            String observedCaptureOwner) {
        this.hookingEnabled = hookingEnabled;
        this.bypassRequested = bypassRequested;
        this.profile = profile != null ? profile : "";
        this.expiresAtMs = expiresAtMs;
        this.inertReason = inertReason;
        this.observedCaptureOwner = observedCaptureOwner != null ? observedCaptureOwner : "";
    }

    /** Fail-closed default used whenever policy is unknown or unavailable. */
    public static AppConfig disabled() {
        return new AppConfig(false, true, "", 0L, InertReason.POLICY_UNAVAILABLE, "");
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
        String observedOwner = snapshot.captureOwner(packageName, processName);
        boolean ownedByLsposed = "lsposed".equals(observedOwner);
        String profile = globallyEnabled && processAllowed && ownedByLsposed
                ? snapshot.resolveProfile(packageName)
                : "";
        // The snapshot contract requires both explicit process policy and a resolvable
        // per-package preset. Never run with a stale native profile after a binding was deleted.
        boolean allowed = globallyEnabled && processAllowed && ownedByLsposed && !profile.isEmpty();
        long expiresAt = SystemClock.elapsedRealtime() + DEFAULT_TTL_MS;
        InertReason reason = reasonFor(
                snapshot.isValid(), globallyEnabled, processAllowed, ownedByLsposed, profile);
        return new AppConfig(
                allowed,
                !allowed,
                allowed ? profile : "",
                expiresAt,
                reason,
                observedOwner);
    }

    /**
     * Classifies a denial after the fact. Reads only the gate results {@link #fromSnapshot} already
     * computed, so it cannot influence them; an unusable snapshot is reported as such rather than as
     * the "engine disabled" it also happens to look like.
     */
    private static InertReason reasonFor(
            boolean snapshotValid,
            boolean globallyEnabled,
            boolean processAllowed,
            boolean ownedByLsposed,
            String profile) {
        if (!snapshotValid) {
            return InertReason.POLICY_UNAVAILABLE;
        }
        if (!globallyEnabled) {
            return InertReason.ENGINE_DISABLED;
        }
        if (!processAllowed) {
            return InertReason.NOT_WHITELISTED;
        }
        if (!ownedByLsposed) {
            return InertReason.NOT_CAPTURE_OWNER;
        }
        if (profile.isEmpty()) {
            return InertReason.NO_PROFILE;
        }
        return InertReason.NONE;
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

    /** Why this process is inert, or {@link InertReason#NONE} when hooking is permitted. */
    public InertReason inertReason() {
        return inertReason;
    }

    /** Capture owner policy assigned to this process ({@code ""} when policy assigned none). */
    public String observedCaptureOwner() {
        return observedCaptureOwner;
    }

    /** One log-ready sentence explaining the current decision, naming the observed owner. */
    public String inertDescription() {
        if (inertReason == InertReason.NOT_CAPTURE_OWNER) {
            return inertReason.description()
                    + " (observed owner: "
                    + (observedCaptureOwner.isEmpty() ? "none" : observedCaptureOwner)
                    + ")";
        }
        return inertReason.description();
    }

    public boolean isExpired() {
        return expiresAtMs > 0 && SystemClock.elapsedRealtime() > expiresAtMs;
    }
}
