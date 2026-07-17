package com.echidna.app.ui.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.echidna.app.data.ControlStateRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose test for [OnboardingWizardHost]. Uses a reduced 3-step flow
 * (Welcome -> Recovery -> Done) so the interaction contract — progress, advancing, the recovery
 * acknowledgement gate, and the finish hand-off — is exercised without the heavier steps'
 * permission/probe side effects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnboardingWizardHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val repo = ControlStateRepository
    private val original = repo.onboardingComplete.value

    @After
    fun tearDown() {
        repo.setOnboardingComplete(original)
    }

    private fun host(steps: List<OnboardingStep>): Pair<OnboardingViewModel, () -> Boolean> {
        val vm = OnboardingViewModel(steps = steps)
        var finished = false
        composeRule.setContent {
            MaterialTheme {
                OnboardingWizardHost(
                    viewModel = vm,
                    onFinished = { finished = true },
                    onOpenInstaller = {},
                    onOpenLab = {},
                )
            }
        }
        return vm to { finished }
    }

    @Test
    fun rendersProgressAndFirstStep() {
        host(listOf(OnboardingStep.WELCOME, OnboardingStep.DONE))
        composeRule.onNodeWithTag(OnboardingTestTags.PROGRESS).assertExists()
        composeRule.onNodeWithTag(OnboardingTestTags.step(OnboardingStep.WELCOME)).assertExists()
        composeRule.onNodeWithTag(OnboardingTestTags.NEXT).assertIsEnabled()
    }

    @Test
    fun next_advancesToTheFollowingStep() {
        val (vm, _) = host(listOf(OnboardingStep.WELCOME, OnboardingStep.DONE))
        composeRule.onNodeWithTag(OnboardingTestTags.NEXT).performClick()
        composeRule.waitForIdle()
        assertEquals(OnboardingStep.DONE, vm.state.value.step)
        composeRule.onNodeWithTag(OnboardingTestTags.step(OnboardingStep.DONE)).assertExists()
    }

    @Test
    fun recoveryStep_disablesNext_untilAcknowledged() {
        val (vm, _) = host(listOf(OnboardingStep.RECOVERY, OnboardingStep.DONE))
        // On the recovery step the Next and Skip actions are gated off.
        composeRule.onNodeWithTag(OnboardingTestTags.NEXT).assertIsNotEnabled()
        composeRule.onNodeWithTag(OnboardingTestTags.SKIP_STEP).assertIsNotEnabled()

        // Acknowledge, and Next becomes enabled. The checkbox lives in the scrollable content, so
        // scroll it into view before clicking.
        composeRule.onNodeWithTag(OnboardingTestTags.RECOVERY_ACK).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue("ack click should reach the VM", vm.state.value.recoveryAcknowledged)
        composeRule.onNodeWithTag(OnboardingTestTags.NEXT).assertIsEnabled()
    }

    @Test
    fun finishingLastStep_persistsCompletionAndCallsBack() {
        repo.setOnboardingComplete(false)
        val (_, finished) = host(listOf(OnboardingStep.DONE))
        // Single-step flow: the primary button finishes immediately.
        composeRule.onNodeWithTag(OnboardingTestTags.NEXT).performClick()
        composeRule.waitForIdle()
        assertTrue("onFinished should fire", finished())
        assertTrue("flag should persist", repo.onboardingComplete.value)
    }

    @Test
    fun skipSetup_finishesTheWholeWizard() {
        repo.setOnboardingComplete(false)
        val (_, finished) = host(listOf(OnboardingStep.WELCOME, OnboardingStep.DONE))
        composeRule.onNodeWithTag(OnboardingTestTags.SKIP_ALL).performClick()
        composeRule.waitForIdle()
        assertTrue(finished())
        assertTrue(repo.onboardingComplete.value)
    }
}
