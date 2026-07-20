package com.echidna.app.ui.diagnostics

import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.LatencyMode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DiagnosticsViewModel] exposes the shared repository state read-only and owns two behaviours of
 * its own: the bounded `refreshCompatibility` probe (whose `probing` flag drives a button's
 * disabled state) and the export entry points, which must return null rather than a fabricated
 * report when the privileged control service is not bound.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {

    private val repo = ControlStateRepository
    private var originalOptIn = false

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        originalOptIn = repo.telemetryOptIn.value
    }

    @After
    fun tearDown() {
        repo.setTelemetryOptIn(originalOptIn)
        Dispatchers.resetMain()
    }

    private fun DiagnosticsViewModel.awaitProbeFinished() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L)
        while (System.nanoTime() < deadline) {
            if (!probing.value && compatibility.value != null) return
            Thread.sleep(2L)
        }
        fail("probe never finished: probing=${probing.value} compatibility=${compatibility.value}")
    }

    @Test
    fun `probing flag starts clear and returns to clear after a refresh`() {
        val vm = DiagnosticsViewModel()
        assertFalse("nothing is probing before the button is pressed", vm.probing.value)

        vm.refreshCompatibility()
        vm.awaitProbeFinished()

        assertFalse("the probe button must re-enable itself", vm.probing.value)
    }

    @Test
    fun `refresh publishes the repository's unavailable verdict without fabricating support`() {
        val vm = DiagnosticsViewModel()
        vm.refreshCompatibility()
        vm.awaitProbeFinished()

        val result = vm.compatibility.value!!
        assertEquals("Unknown (control service unavailable)", result.selinuxStatus)
        assertTrue(
            "an unbound service must not report any supported audio path",
            result.audioStack.none { it.supported },
        )
    }

    @Test
    fun `a second refresh is safe and leaves the flag clear`() {
        val vm = DiagnosticsViewModel()
        vm.refreshCompatibility()
        vm.awaitProbeFinished()
        vm.refreshCompatibility()
        vm.awaitProbeFinished()

        assertFalse(vm.probing.value)
        assertNotNull(vm.compatibility.value)
    }

    @Test
    fun `telemetry export returns null when the control service is unbound`() {
        val vm = DiagnosticsViewModel()
        var called = false
        var payload: String? = "sentinel"
        vm.exportTelemetry(includeTrends = true) { result ->
            called = true
            payload = result
        }

        assertTrue("the callback must always fire", called)
        assertNull("no telemetry may be invented without the service", payload)
    }

    @Test
    fun `diagnostics export returns null when the control service is unbound`() {
        val vm = DiagnosticsViewModel()
        var called = false
        var payload: String? = "sentinel"
        vm.exportDiagnostics(includeTrends = false) { result ->
            called = true
            payload = result
        }

        assertTrue("the callback must always fire", called)
        assertNull("no diagnostics bundle may be invented without the service", payload)
    }

    @Test
    fun `telemetry opt-in is written through to the shared repository`() {
        val vm = DiagnosticsViewModel()

        vm.setTelemetryOptIn(true)
        assertTrue(repo.telemetryOptIn.value)
        assertTrue(vm.telemetryOptIn.value)

        vm.setTelemetryOptIn(false)
        assertFalse(repo.telemetryOptIn.value)
        assertFalse(vm.telemetryOptIn.value)
    }

    @Test
    fun `the read-only flows stay live after the view model is constructed`() {
        val vm = DiagnosticsViewModel()
        val originalMaster = repo.masterEnabled.value
        val originalBypass = repo.bypass.value
        val originalMode = repo.latencyMode.value
        try {
            // The Diagnostics screen renders these; they must be the repository's live flows, not
            // detached snapshots taken at construction time.
            repo.setMasterEnabled(!originalMaster)
            repo.setBypass(!originalBypass)
            repo.setLatencyMode(
                if (originalMode == LatencyMode.LOW_LATENCY) LatencyMode.HIGH_QUALITY
                else LatencyMode.LOW_LATENCY
            )

            assertEquals(!originalMaster, vm.masterEnabled.value)
            assertEquals(!originalBypass, vm.bypass.value)
            assertNotEquals(originalMode, vm.latencyMode.value)
            assertEquals(repo.engineStatus.value, vm.engineStatus.value)
        } finally {
            repo.setMasterEnabled(originalMaster)
            repo.setBypass(originalBypass)
            repo.setLatencyMode(originalMode)
        }
    }
}
