package com.echidna.app.ui.diagnostics

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.CompatibilityResult
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose coverage for the Diagnostics screen.
 *
 * Every assertion here is about the screen with an UNBOUND control service — the state a normal,
 * unrooted device is in — because that is where the screen's honesty contract lives: no module
 * status means "Not bound"/"Unknown", no telemetry means empty histograms and an Idle engine, and
 * telemetry export stays disabled until the user opts in. The five tabs are also proven to be real
 * content switches rather than decoration.
 *
 * A large display is configured so the screen's LazyColumn composes its cards; the tests are about
 * content and interaction, not about which card happens to fit a phone viewport.
 *
 * CROSS-CLASS ORDER INDEPENDENCE. `ControlStateRepository` is a process singleton whose
 * `compatibilityState` starts null and becomes the "control service unavailable" placeholder as
 * soon as ANY test in the JVM runs a probe — this class's probe test does it, and so do
 * `DiagnosticsViewModelTest` and `CompatibilityWizardViewModelTest`. Gradle does not guarantee
 * test-class ordering (it varies with worker scheduling and core count), so a test that assumed
 * either value would pass or fail depending on what happened to run first.
 *
 * The repository exposes NO seam to reset that field: `runCompatibilityProbe()` is the only public
 * mutator and it always lands on a non-null result. So rather than assume a pristine process, the
 * `@Before` below ESTABLISHES the post-probe state for every test. Each test then starts from the
 * same known point no matter what ran before it, in any order, in any JVM.
 *
 * Consequence, stated plainly: the `compatibility == null` branch of the overview probe card is no
 * longer covered here, because it is unreachable once anything has probed and there is no reset.
 * Determinism was preferred over that one branch. Method order is still pinned to NAME_ASCENDING
 * so the class reads reproducibly, but correctness no longer depends on it.
 *
 * Assertions use exact text matching, never substring, so the placeholder's
 * "Latest probe: SELinux Unknown (control service unavailable)" cannot satisfy a bare "Unknown",
 * and they assert node COUNTS so an unexpected extra match fails loudly instead of passing.
 */
@RunWith(RobolectricTestRunner::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Config(sdk = [33], qualifiers = "w1000dp-h2400dp")
class DiagnosticsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val repo = ControlStateRepository

    private var savedMaster = true
    private var savedBypass = false
    private var savedOptIn = false

    @Before
    fun captureRepositoryState() {
        savedMaster = repo.masterEnabled.value
        savedBypass = repo.bypass.value
        savedOptIn = repo.telemetryOptIn.value
        establishProbedCompatibilityState()
    }

    /**
     * Drives the singleton to a known compatibility state so no test depends on whether some other
     * class in this JVM probed first. Idempotent: probing again from an already-probed process
     * lands on the same "control service unavailable" placeholder.
     */
    private fun establishProbedCompatibilityState() {
        repo.runCompatibilityProbe()
        awaitCompatibility { it != null }
    }

    /** Bounded spin-wait; the probe completes on the repository's own scope, off the test thread. */
    private fun awaitCompatibility(predicate: (CompatibilityResult?) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L)
        while (System.nanoTime() < deadline) {
            if (predicate(repo.compatibilityState.value)) return
            Thread.sleep(2L)
        }
        fail("compatibilityState never satisfied the predicate: ${repo.compatibilityState.value}")
    }

    @After
    fun restoreRepositoryState() {
        repo.setMasterEnabled(savedMaster)
        repo.setBypass(savedBypass)
        repo.setTelemetryOptIn(savedOptIn)
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme { DiagnosticsScreen(viewModel = DiagnosticsViewModel()) }
        }
    }

    /** Tabs merge their label into a selectable node, so the OnClick semantics drive the switch. */
    private fun selectTab(title: String) {
        composeRule.onNodeWithText(title).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    private fun assertShown(text: String, substring: Boolean = false, count: Int = 1) =
        composeRule.onAllNodesWithText(text, substring = substring).assertCountEquals(count)

    private fun assertAbsent(text: String, substring: Boolean = false) =
        composeRule.onAllNodesWithText(text, substring = substring).assertCountEquals(0)

    // -----------------------------------------------------------------------
    // Overview
    // -----------------------------------------------------------------------

    @Test
    fun `the screen opens on the overview tab and offers all five tabs`() {
        setContent()
        assertShown("Diagnostics")
        assertShown("Overview")
        assertShown("Pipeline & hooks")
        assertShown("Latency & CPU")
        assertShown("Tuner & formant")
        assertShown("Logs & safety")
        // Overview content, and only overview content.
        assertShown("Control plane")
        assertAbsent("Latency histogram")
        assertAbsent("Safety notes")
    }

    @Test
    fun `an unbound control service reports the module and zygisk state as unknown`() {
        setContent()
        assertShown("Not bound")
        assertShown("Unknown")
        // It must not claim the module is installed.
        assertAbsent("Installed")
    }

    @Test
    fun `the control-plane rows follow the live master and bypass flags`() {
        repo.setMasterEnabled(true)
        repo.setBypass(false)
        setContent()
        assertShown("Enabled")
        assertShown("Off")

        repo.setMasterEnabled(false)
        repo.setBypass(true)
        composeRule.waitForIdle()
        // Master now reads Off and bypass reads On — the rows are wired to the live flows.
        assertShown("On")
        assertAbsent("Enabled")
    }

    // -----------------------------------------------------------------------
    // Tab switching
    // -----------------------------------------------------------------------

    @Test
    fun `the pipeline tab replaces the overview with the pipeline, hooks and advanced sections`() {
        setContent()
        selectTab("Pipeline & hooks")

        assertAbsent("Control plane")
        assertShown("Audio pipeline")
        assertShown("Hooks")
        assertShown("No hook attempts recorded yet")
        assertShown("Advanced diagnostics")
    }

    @Test
    fun `the latency tab shows empty histogram and heatmap placeholders instead of invented data`() {
        setContent()
        selectTab("Latency & CPU")

        assertAbsent("Control plane")
        assertShown("Latency histogram")
        assertShown("No latency data yet")
        assertShown("CPU heatmap")
        assertShown("No CPU samples")
        assertShown("Callback timing")
        // No verified processing, so the engine row must read Idle rather than Processing.
        assertShown("Idle")
        assertAbsent("Processing")
    }

    @Test
    fun `the tuner tab renders the tuner and formant readouts`() {
        setContent()
        selectTab("Tuner & formant")

        assertAbsent("Latency histogram")
        assertShown("Tuner")
        assertShown("Formant / pitch")
        assertShown("Detected:", substring = true)
        assertShown("Target:", substring = true)
        assertShown("Offset:", substring = true)
        assertShown("Shift", substring = true)
    }

    @Test
    fun `the logs tab shows the export, probe and safety cards`() {
        setContent()
        selectTab("Logs & safety")

        assertAbsent("Latency histogram")
        assertShown("Telemetry export")
        assertShown("Compatibility probes")
        assertShown("Safety notes")
        assertShown(
            "This screen reads status and probes only; it does not change root, SELinux, or module policy.",
        )
    }

    // -----------------------------------------------------------------------
    // Telemetry export gating
    // -----------------------------------------------------------------------

    @Test
    fun `telemetry export stays disabled until the user opts in`() {
        repo.setTelemetryOptIn(false)
        setContent()
        selectTab("Logs & safety")

        composeRule.onNodeWithText("Export anonymized telemetry").assertIsNotEnabled()
        composeRule.onNodeWithText("Export diagnostic internals").assertIsNotEnabled()

        composeRule.onNode(isToggleable()).performClick()
        composeRule.waitForIdle()

        assertTrue("the switch must write the opt-in through", repo.telemetryOptIn.value)
        composeRule.onNodeWithText("Export anonymized telemetry").assertIsEnabled()
        composeRule.onNodeWithText("Export diagnostic internals").assertIsEnabled()
    }

    // -----------------------------------------------------------------------
    // Compatibility probe
    // -----------------------------------------------------------------------

    @Test
    fun `running the probe publishes the honest unavailable verdict`() {
        setContent()
        selectTab("Logs & safety")

        // @Before already established a probed state, so merely seeing a result card would prove
        // nothing about the button. Each probe publishes a FRESH CompatibilityResult instance, so
        // holding the previous one and waiting for the reference to change is what actually pins
        // that the click ran a new probe rather than leaving a stale verdict on screen.
        val previous = repo.compatibilityState.value
        assertNotNull("the established state must be present before the click", previous)

        composeRule.onNodeWithText("Run compatibility probes").assertIsEnabled().performClick()
        awaitCompatibility { it != null && it !== previous }
        composeRule.waitUntil(PROBE_WAIT_MS) {
            composeRule.onAllNodesWithText("Last compatibility result")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Without a bound service the probe reports that it could not tell, not a pass.
        assertShown("Unknown (control service unavailable)")
        assertShown("Unavailable", substring = true)
        // The button re-enables itself once the bounded probe completes.
        composeRule.onNodeWithText("Run compatibility probes").assertIsEnabled()

        // The overview's probe summary line only exists once a result has been collected.
        selectTab("Overview")
        assertShown("Latest probe: SELinux Unknown (control service unavailable)")
    }

    private companion object {
        const val PROBE_WAIT_MS = 10_000L
    }
}
