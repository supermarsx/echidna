package com.echidna.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.ui.AppDestination
import com.echidna.app.ui.AppNavGraph
import com.echidna.app.ui.onboarding.OnboardingStep
import com.echidna.app.ui.onboarding.OnboardingTestTags
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end first-run gating through the real [AppNavGraph], mounted the way MainActivity gates it
 * (start on the wizard iff onboarding is incomplete):
 *  - the wizard shows at first launch; skipping it hands off to the normal app and persists the flag;
 *  - once complete, the wizard is skipped and the app shows directly.
 *
 * Scope note: this on-device layer proves the gating decision and the real nav handoff. It stays on
 * the Welcome step and skips the wizard rather than traversing the animated Compatibility/Whitelist
 * steps, whose infinite `CircularProgressIndicator` fights the Compose test's `waitForIdle`. The
 * per-step navigation, the recovery-ack gate, finish, and skip-all are covered deterministically by
 * the Robolectric `OnboardingWizardHostTest` + `OnboardingViewModelTest` + `OnboardingUiStateTest`.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class OnboardingFlowInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val repo = ControlStateRepository
    private val original = repo.onboardingComplete.value

    @After
    fun restore() {
        repo.setOnboardingComplete(original)
    }

    /** Mounts the app the way MainActivity gates it: start on the wizard iff onboarding is incomplete. */
    private fun mountGatedApp() {
        composeRule.setContent {
            MaterialTheme {
                val navController = rememberNavController()
                val start = remember {
                    if (repo.onboardingComplete.value) {
                        AppDestination.Dashboard.route
                    } else {
                        AppDestination.Onboarding.route
                    }
                }
                NavHost(navController, startDestination = start) {
                    AppNavGraph(navController)
                }
            }
        }
    }

    @Test
    fun firstRun_showsWizard_thenSkipEntersApp() {
        repo.setOnboardingComplete(false)
        mountGatedApp()

        // 1) First launch shows the wizard on the Welcome step.
        composeRule.onNodeWithTag(OnboardingTestTags.HOST).assertExists()
        composeRule.onNodeWithTag(OnboardingTestTags.step(OnboardingStep.WELCOME)).assertExists()

        // 2) Skip the wizard: it hands off to the app (Dashboard's panic control) and the completion
        //    flag persists so the wizard won't reappear.
        composeRule.onNodeWithTag(OnboardingTestTags.SKIP_ALL).performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Panic — Bypass Engine").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(OnboardingTestTags.HOST).assertDoesNotExist()
        assertTrue(repo.onboardingComplete.value)
    }

    @Test
    fun completedOnboarding_skipsWizard_showsApp() {
        repo.setOnboardingComplete(true)
        mountGatedApp()

        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Panic — Bypass Engine").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(OnboardingTestTags.HOST).assertDoesNotExist()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
