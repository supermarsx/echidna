package com.echidna.app.model

/**
 * Which engine owns audio capture for a whitelisted process.
 *
 * Exactly one owner may transform a given process's buffers, so the companion assigns ownership
 * from the selected [DspEngineMode] before publishing policy. The LSPosed shim fails closed unless
 * it is named [LSPOSED]; that is why installing and enabling the shim while the engine mode is
 * anything else produces a module LSPosed reports as loaded and active that does nothing at all.
 */
enum class CaptureOwner(val id: String, val label: String) {
    /** Native Zygisk module owns capture. The LSPosed shim stays inert. */
    ZYGISK("zygisk", "Zygisk (native)"),

    /** LSPosed shim owns capture, for the Java `AudioRecord` path only. */
    LSPOSED("lsposed", "LSPosed shim (Java AudioRecord)"),

    /** No owner is published, so nothing is hooked. */
    NONE("", "none"),
}

/** Why no engine currently owns capture, or [ACTIVE] when one does. */
enum class CaptureOwnerReason(val summary: String) {
    ACTIVE("An engine owns capture for the whitelisted apps."),
    ENGINE_DISABLED(
        "Master processing is off, bypass is engaged, or a panic hold is active, so no capture " +
            "owner is published."
    ),
    NO_WHITELISTED_APPS(
        "No app is enabled in the Per-App Whitelist, so ownership is assigned to nothing. Enable " +
            "your target apps under Settings -> Per-App Whitelist."
    ),
    POLICY_NOT_PUBLISHED(
        "The companion has not yet published an authoritative whitelist and preset binding set, " +
            "so no process has an owner."
    ),
}

/** Effective capture ownership and, when there is none, the reason. */
data class CaptureOwnerStatus(
    val owner: CaptureOwner,
    val reason: CaptureOwnerReason,
) {
    /** One line suitable for a diagnostics row value. */
    val summary: String
        get() = if (reason == CaptureOwnerReason.ACTIVE) owner.label else CaptureOwner.NONE.label
}

/**
 * Single source of truth for capture-owner assignment. [ControlStateRepository][com.echidna.app.data.ControlStateRepository]
 * uses this when encoding policy, and Diagnostics uses it to show the user the same answer the shim
 * will see, so the two can never disagree.
 */
object CaptureOwnership {

    /** Owner assigned to every enabled whitelist entry for [engineMode]. */
    fun ownerFor(engineMode: DspEngineMode): CaptureOwner =
        if (engineMode == DspEngineMode.COMPATIBILITY) CaptureOwner.LSPOSED else CaptureOwner.ZYGISK

    /**
     * Resolves the ownership a target app would actually see, applying the same gates the published
     * policy does: an authoritative policy must exist, the engine must be globally on, and at least
     * one app must be whitelisted.
     */
    fun resolve(
        engineMode: DspEngineMode,
        masterEnabled: Boolean,
        bypass: Boolean,
        panicUntilEpochMs: Long,
        nowEpochMs: Long,
        enabledWhitelistCount: Int,
        policyPublished: Boolean,
    ): CaptureOwnerStatus {
        val panicHeld = panicUntilEpochMs > 0L && nowEpochMs < panicUntilEpochMs
        val reason = when {
            !policyPublished -> CaptureOwnerReason.POLICY_NOT_PUBLISHED
            !masterEnabled || bypass || panicHeld -> CaptureOwnerReason.ENGINE_DISABLED
            enabledWhitelistCount == 0 -> CaptureOwnerReason.NO_WHITELISTED_APPS
            else -> CaptureOwnerReason.ACTIVE
        }
        return CaptureOwnerStatus(
            owner = if (reason == CaptureOwnerReason.ACTIVE) {
                ownerFor(engineMode)
            } else {
                CaptureOwner.NONE
            },
            reason = reason,
        )
    }
}
