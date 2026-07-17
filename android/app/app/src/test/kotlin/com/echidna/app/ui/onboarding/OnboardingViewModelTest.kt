package com.echidna.app.ui.onboarding

import com.echidna.app.data.ControlStateRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [OnboardingViewModel]: navigation gating plus the reused persistence
 * side-effects on the shared [ControlStateRepository] singleton (org.json needs Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OnboardingViewModelTest {

    private val repo = ControlStateRepository
    private val originalOnboarding = repo.onboardingComplete.value
    private val originalSettings = repo.settingsState.value

    @After
    fun tearDown() {
        repo.setOnboardingComplete(originalOnboarding)
        repo.setShowInstallAlerts(originalSettings.showInstallAlerts)
        repo.setShowBridgeAlerts(originalSettings.showBridgeAlerts)
        repo.setShowHardwareAlerts(originalSettings.showHardwareAlerts)
        repo.setShowInstallMixupAlerts(originalSettings.showInstallMixupAlerts)
    }

    @Test
    fun next_advancesThroughSteps() {
        val vm = OnboardingViewModel()
        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)
        vm.next()
        assertEquals(OnboardingStep.PERMISSIONS, vm.state.value.step)
        vm.back()
        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)
    }

    @Test
    fun recoveryStep_blocksNext_untilAcknowledged() {
        val vm = OnboardingViewModel()
        vm.goTo(OnboardingStep.ordered.indexOf(OnboardingStep.RECOVERY))
        assertEquals(OnboardingStep.RECOVERY, vm.state.value.step)

        vm.next() // blocked
        assertEquals(OnboardingStep.RECOVERY, vm.state.value.step)

        vm.acknowledgeRecovery(true)
        vm.next()
        assertEquals(OnboardingStep.THEME, vm.state.value.step)
    }

    @Test
    fun skipStep_advancesWithDefaults_butStillGatesRecovery() {
        val vm = OnboardingViewModel()
        vm.goTo(OnboardingStep.ordered.indexOf(OnboardingStep.RECOVERY))
        vm.skipStep() // must be blocked on recovery too
        assertEquals(OnboardingStep.RECOVERY, vm.state.value.step)

        vm.acknowledgeRecovery(true)
        vm.skipStep()
        assertEquals(OnboardingStep.THEME, vm.state.value.step)
    }

    @Test
    fun setAlertsEnabled_flipsAllFourAdvisoryCategories() {
        val vm = OnboardingViewModel()
        vm.setAlertsEnabled(false)
        repo.settingsState.value.let {
            assertFalse(it.showInstallAlerts)
            assertFalse(it.showBridgeAlerts)
            assertFalse(it.showHardwareAlerts)
            assertFalse(it.showInstallMixupAlerts)
        }
        vm.setAlertsEnabled(true)
        repo.settingsState.value.let {
            assertTrue(it.showInstallAlerts)
            assertTrue(it.showBridgeAlerts)
            assertTrue(it.showHardwareAlerts)
            assertTrue(it.showInstallMixupAlerts)
        }
    }

    @Test
    fun finishNow_marksCompleteAndFinished() {
        repo.setOnboardingComplete(false)
        val vm = OnboardingViewModel()
        vm.finishNow()
        assertTrue(vm.state.value.finished)
        assertTrue(repo.onboardingComplete.value)
    }

    @Test
    fun advancingOffLastStep_persistsCompletion() {
        repo.setOnboardingComplete(false)
        val vm = OnboardingViewModel()
        vm.goTo(OnboardingStep.ordered.lastIndex)
        assertEquals(OnboardingStep.DONE, vm.state.value.step)
        vm.next()
        assertTrue(vm.state.value.finished)
        assertTrue(repo.onboardingComplete.value)
    }

    @Test
    fun markComplete_persistsWithoutFinishingNavigation() {
        repo.setOnboardingComplete(false)
        val vm = OnboardingViewModel()
        vm.markComplete()
        assertTrue(repo.onboardingComplete.value)
        assertFalse("markComplete must not trigger the finish navigation", vm.state.value.finished)
    }
}
