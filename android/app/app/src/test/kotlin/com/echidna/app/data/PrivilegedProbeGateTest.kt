package com.echidna.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the first-run su grant loop (t17): the automatic privileged status probe must be
 * gated on onboarding completion (so the Welcome page triggers zero background root) and must arm
 * at most once (so the poll/launch path never re-requests root per cycle).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PrivilegedProbeGateTest {

    private val repo = ControlStateRepository

    @Test
    fun `onboarding-incomplete never claims the background privileged probe`() {
        val original = repo.onboardingComplete.value
        try {
            repo.resetPrivilegedProbeForTest()
            repo.setOnboardingComplete(false)
            assertFalse(
                "onboarding incomplete must not arm the background root probe",
                repo.claimPrivilegedProbeIfEligible(),
            )
            assertFalse(
                "still not armed on a second pass while onboarding is incomplete",
                repo.claimPrivilegedProbeIfEligible(),
            )
        } finally {
            repo.setOnboardingComplete(original)
            repo.resetPrivilegedProbeForTest()
        }
    }

    @Test
    fun `completing onboarding arms the probe exactly once`() {
        val original = repo.onboardingComplete.value
        try {
            repo.setOnboardingComplete(true)
            repo.resetPrivilegedProbeForTest()
            assertTrue(
                "completing onboarding arms the one-time probe",
                repo.claimPrivilegedProbeIfEligible(),
            )
            assertFalse(
                "the probe is armed at most once, not per poll cycle",
                repo.claimPrivilegedProbeIfEligible(),
            )
        } finally {
            repo.setOnboardingComplete(original)
            repo.resetPrivilegedProbeForTest()
        }
    }
}
