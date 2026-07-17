package com.echidna.app.ui.onboarding

/**
 * The ordered steps of the first-run setup wizard (t14). Every step is skippable with a sane
 * default; [RECOVERY] is the one exception — the user must acknowledge the recovery plan before the
 * wizard will advance past it (hardening report §20).
 */
enum class OnboardingStep(val shortLabel: String, val title: String) {
    WELCOME("Welcome", "Welcome to Echidna"),
    PERMISSIONS("Permissions", "Permissions"),
    COMPATIBILITY("Compatibility", "Device compatibility"),
    RECOVERY("Recovery", "Before you install a root module"),
    THEME("Theme", "Make it yours"),
    PRESET("Preset", "Pick a starting preset"),
    WHITELIST("Apps", "Choose target apps"),
    ALERTS("Alerts", "Advisory alerts"),
    HIGH_PRIORITY_NOTIFICATION("Notification", "Controls notification"),
    QUICK_TILE("Tile", "Quick Settings tile"),
    ENGINE("Engine", "Interception engine"),
    LAB("Lab", "Hear it work"),
    DONE("Done", "You're set up");

    companion object {
        /** Canonical wizard order. */
        val ordered: List<OnboardingStep> = entries.toList()
    }
}

/**
 * Pure, Android-free navigation state for the wizard. Transitions are expressed as functions that
 * return a new immutable state, so the step math and the recovery gate are unit-testable without
 * Robolectric or Compose. The [OnboardingViewModel] owns one of these in a StateFlow and layers
 * persistence on top.
 */
data class OnboardingUiState(
    val steps: List<OnboardingStep> = OnboardingStep.ordered,
    val index: Int = 0,
    val recoveryAcknowledged: Boolean = false,
    val finished: Boolean = false,
) {
    val step: OnboardingStep get() = steps[index.coerceIn(0, steps.lastIndex)]
    val isFirst: Boolean get() = index <= 0
    val isLast: Boolean get() = index >= steps.lastIndex
    val stepNumber: Int get() = index + 1
    val totalSteps: Int get() = steps.size
    val progress: Float get() = stepNumber.toFloat() / totalSteps

    /**
     * True when the wizard is allowed to move off the current step. Only the recovery step gates
     * advancing: it requires an explicit acknowledgement first. This governs both "Next" and
     * per-step "Skip" so a user cannot slip past the recovery plan without acknowledging it.
     */
    val canAdvance: Boolean
        get() = step != OnboardingStep.RECOVERY || recoveryAcknowledged

    /** Advances one step when allowed; on the last step marks the wizard finished. */
    fun advanced(): OnboardingUiState = when {
        !canAdvance -> this
        isLast -> copy(finished = true)
        else -> copy(index = index + 1)
    }

    /** Goes back one step (no-op on the first step). Back is always allowed. */
    fun back(): OnboardingUiState =
        if (isFirst) this else copy(index = index - 1)

    fun withRecoveryAck(acknowledged: Boolean): OnboardingUiState =
        copy(recoveryAcknowledged = acknowledged)

    /** Jumps directly to a step index, clamped to the valid range. */
    fun goTo(target: Int): OnboardingUiState =
        copy(index = target.coerceIn(0, steps.lastIndex))
}
