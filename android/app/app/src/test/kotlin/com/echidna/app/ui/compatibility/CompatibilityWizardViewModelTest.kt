package com.echidna.app.ui.compatibility

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fail-closed contract for the compatibility wizard.
 *
 * The shared `ControlStateRepository` singleton has no bound control service in a JVM test, so the
 * probe resolves to the repository's honest "unavailable" placeholder. What is pinned here is that
 * the wizard surfaces that placeholder verbatim instead of reporting a supported stack, and that
 * the `running` flag always clears so the retry button can never be left permanently greyed out.
 *
 * `Dispatchers.Main` is replaced with the unconfined dispatcher so `viewModelScope` work runs
 * inline; the repository's own probe still runs on `Dispatchers.Default`, hence [awaitSettled].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class CompatibilityWizardViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Blocks until the wizard leaves the probing state, then returns the settled UI state. */
    private fun CompatibilityWizardViewModel.awaitSettled(): CompatibilityWizardUiState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L)
        while (System.nanoTime() < deadline) {
            val state = uiState.value
            if (!state.running && state.result != null) return state
            Thread.sleep(2L)
        }
        fail("wizard never settled: ${uiState.value}")
        error("unreachable")
    }

    @Test
    fun `construction kicks off a probe that settles into a result`() {
        val vm = CompatibilityWizardViewModel()
        val settled = vm.awaitSettled()

        assertFalse("the retry button must not stay disabled", settled.running)
        assertNotNull(settled.result)
    }

    @Test
    fun `an unbound control service yields an unavailable verdict rather than a fabricated one`() {
        val result = CompatibilityWizardViewModel().awaitSettled().result!!

        assertEquals("Unknown (control service unavailable)", result.selinuxStatus)
        assertEquals(1, result.audioStack.size)
        val probe = result.audioStack.single()
        assertEquals("Control service", probe.name)
        assertFalse("an unbound service must never report a supported stack", probe.supported)
        assertEquals(
            "no latency may be estimated without a probe",
            null,
            probe.latencyEstimateMs,
        )
        assertTrue(
            "the notes must tell the user what to do",
            result.notes.any { it.contains("control service", ignoreCase = true) },
        )
    }

    @Test
    fun `re-running the probe replaces the result without stranding the running flag`() {
        val vm = CompatibilityWizardViewModel()
        val first = vm.awaitSettled()

        vm.runProbes()
        val second = vm.awaitSettled()

        assertFalse(second.running)
        assertEquals(first.result, second.result)
    }
}
