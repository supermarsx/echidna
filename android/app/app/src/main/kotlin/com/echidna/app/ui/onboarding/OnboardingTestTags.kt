package com.echidna.app.ui.onboarding

/** Stable semantics tags so UI tests can drive the wizard without depending on copy. */
object OnboardingTestTags {
    const val HOST = "onboarding_host"
    const val PROGRESS = "onboarding_progress"
    const val NEXT = "onboarding_next"
    const val BACK = "onboarding_back"
    const val SKIP_STEP = "onboarding_skip_step"
    const val SKIP_ALL = "onboarding_skip_all"
    const val RECOVERY_ACK = "onboarding_recovery_ack"
    const val ALERTS_TOGGLE = "onboarding_alerts_toggle"
    const val HIGH_PRIORITY_TOGGLE = "onboarding_high_priority_toggle"
    const val QUICK_TILE_TOGGLE = "onboarding_quick_tile_toggle"

    /** Per-step content root tag, e.g. `onboarding_step_WELCOME`. */
    fun step(step: OnboardingStep): String = "onboarding_step_${step.name}"
}
