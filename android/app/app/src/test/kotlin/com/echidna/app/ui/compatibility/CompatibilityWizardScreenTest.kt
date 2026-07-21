package com.echidna.app.ui.compatibility

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose coverage for [CompatibilityWizardScreen].
 *
 * There is no bound control service in a JVM test, so the probe resolves to the repository's honest
 * "unavailable" placeholder. What is pinned here is that the *screen* renders that placeholder
 * verbatim — an "Unavailable" audio-path row, an "Unknown (control service unavailable)" SELinux
 * value and a blocked headline — instead of inventing a supported stack or a latency number, plus
 * the call-to-action / result action-bar swap and the finish hand-off.
 *
 * `Dispatchers.Main` is a [StandardTestDispatcher] so the view model's `init` probe does NOT run
 * until the test asks for it; that makes the pre-probe branch of `WizardActions` (a plain "Run
 * probes" button, no result cards) deterministically observable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class CompatibilityWizardScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun screen(onFinish: () -> Unit = {}): CompatibilityWizardViewModel {
        val vm = CompatibilityWizardViewModel()
        composeRule.setContent {
            MaterialTheme { CompatibilityWizardScreen(viewModel = vm, onFinish = onFinish) }
        }
        return vm
    }

    /**
     * Pumps the test dispatcher (the view model's coroutine) and the Compose clock until the wizard
     * has a result on screen. The repository's own probe runs on `Dispatchers.Default`, so a single
     * `advanceUntilIdle` is not enough — this is the bounded settle the other repository-backed
     * tests use, adapted to also let Compose recompose.
     */
    private fun CompatibilityWizardViewModel.settle() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L)
        while (System.nanoTime() < deadline) {
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
            val state = uiState.value
            if (!state.running && state.result != null) return
            Thread.sleep(2L)
        }
        fail("wizard never settled: ${uiState.value}")
    }

    private fun scrollTo(matcher: SemanticsMatcher) =
        composeRule.onNode(hasScrollAction()).performScrollToNode(matcher)

    @Test
    fun `before any probe has run the screen offers to run one and claims nothing`() {
        screen()
        // The view model's init probe is queued on the paused main dispatcher and has not started.
        composeRule.onNodeWithText("Compatibility Wizard").assertExists()
        composeRule.onNodeWithText("Run probes").assertExists()
        composeRule.onNodeWithText("Run probes").assertIsEnabled()
        // No verdict may be shown before a probe has produced one.
        composeRule.onNodeWithText("SELinux").assertDoesNotExist()
        composeRule.onNodeWithText("Device environment").assertDoesNotExist()
        composeRule.onNodeWithText("Finish").assertDoesNotExist()
    }

    @Test
    fun `an unbound control service renders the unavailable placeholder, not a fabricated verdict`() {
        screen().settle()

        scrollTo(hasText("Device environment"))
        composeRule.onNodeWithText("SELinux").assertExists()
        composeRule.onNodeWithText("Unknown (control service unavailable)").assertExists()
        composeRule.onNodeWithText("The probe could not confirm SELinux compatibility.").assertExists()

        scrollTo(hasText("Audio path"))
        composeRule.onNodeWithText("Control service").assertExists()
        // The honest word for "we could not probe" — never "Supported" and never a latency figure.
        composeRule.onNodeWithText("Unavailable").assertExists()
        composeRule.onNodeWithText("Not bound — SELinux/HAL probes unavailable").assertExists()
        composeRule.onNodeWithText("Supported", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the overview headline and tally reflect the failing checks`() {
        screen().settle()

        scrollTo(hasText("Blocked until service access works"))
        composeRule.onNodeWithText("Blocked until service access works").assertExists()
        // SELinux + the control-service probe fail; the single note passes.
        composeRule.onNodeWithText("1 pass | 0 warn | 2 fail").assertExists()
        composeRule.onNodeWithText("Ready for native audio probes").assertDoesNotExist()
        composeRule.onNodeWithText("Usable with notes to review").assertDoesNotExist()
    }

    @Test
    fun `the action-notes group renders the service's note`() {
        screen().settle()

        scrollTo(hasText("Action notes"))
        composeRule.onNodeWithText("Action notes").assertExists()
        composeRule
            .onNodeWithText("Bind the Echidna control service to run SELinux and HAL probes.")
            .assertExists()
    }

    @Test
    fun `once a result exists the call-to-action becomes run-again plus finish`() {
        var finished = false
        val vm = screen(onFinish = { finished = true })
        vm.settle()

        scrollTo(hasText("Finish"))
        composeRule.onNodeWithText("Run again").assertExists()
        composeRule.onNodeWithText("Finish").assertIsEnabled()
        // The pre-probe single button is gone once there is something to report.
        composeRule.onNodeWithText("Run probes").assertDoesNotExist()

        assertFalse(finished)
        composeRule.onNodeWithText("Finish").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertTrue("Finish must hand back to the caller", finished)
    }

    @Test
    fun `run again re-probes and keeps the honest verdict`() {
        val vm = screen()
        vm.settle()

        scrollTo(hasText("Run again"))
        composeRule.onNodeWithText("Run again").performSemanticsAction(SemanticsActions.OnClick)
        vm.settle()

        scrollTo(hasText("Device environment"))
        composeRule.onNodeWithText("Unknown (control service unavailable)").assertExists()
        assertFalse("the retry button must never stay disabled", vm.uiState.value.running)
    }
}
