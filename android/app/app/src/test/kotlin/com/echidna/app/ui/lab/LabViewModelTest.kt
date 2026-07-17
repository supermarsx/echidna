package com.echidna.app.ui.lab

import com.echidna.app.audio.DspProcessor
import com.echidna.app.audio.DspProcessorFactory
import com.echidna.app.model.EffectModule
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.Preset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric so android.util.Log (touched by EchidnaLabDsp's static init) is available.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class LabViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private fun preset(name: String, dryWet: Int) = Preset(
        id = name.lowercase(),
        name = name,
        description = null,
        tags = emptySet(),
        latencyMode = LatencyMode.LOW_LATENCY,
        dryWet = dryWet,
        modules = listOf(EffectModule.Mix(true, dryWet.toFloat(), 0f))
    )

    private val presets = listOf(preset("Alpha", 60), preset("Beta", 80))

    /** Fake factory: applies a fixed gain so wet clearly differs from dry. */
    private val gainFactory = DspProcessorFactory { _, _, _, _ ->
        object : DspProcessor {
            override val available = true
            override fun process(input: FloatArray, output: FloatArray, frames: Int): Boolean {
                for (i in 0 until frames) output[i] = (input[i] * 2f).coerceIn(-1f, 1f)
                return true
            }
            override fun close() {}
        }
    }

    private fun viewModel(engineAvailable: Boolean = true) =
        LabViewModel(gainFactory, presets, dispatcher) { engineAvailable }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialStateSelectsFirstPresetAndReportsEngine() {
        val vm = viewModel()
        val s = vm.state.value
        assertEquals("alpha", s.selectedPresetId)
        assertTrue(s.engineAvailable)
        assertEquals(2, s.presets.size)
    }

    @Test
    fun micActionsAreGatedOnPermission() {
        val vm = viewModel()
        vm.startMeter()
        assertEquals(MicMode.IDLE, vm.state.value.micMode) // no permission -> ignored

        vm.onPermissionResult(false)
        assertFalse(vm.state.value.permissionGranted)
        assertNotNull(vm.state.value.message)

        vm.onPermissionResult(true)
        assertTrue(vm.state.value.permissionGranted)
    }

    @Test
    fun selectPresetUpdatesSelection() {
        val vm = viewModel()
        vm.selectPreset(presets[1])
        assertEquals("beta", vm.state.value.selectedPresetId)
    }

    @Test
    fun generateTestToneSetsDrySource() {
        val vm = viewModel()
        assertFalse(vm.state.value.hasDrySource)
        vm.selectTestTone(com.echidna.app.audio.TestTone.Kind.SINE_440)
        vm.generateTestTone()
        val s = vm.state.value
        assertTrue(s.hasDrySource)
        assertTrue(s.dryLabel.contains("Test tone"))
        assertNotNull(s.dryWaveform)
    }

    @Test
    fun processProducesWetThatDiffersAndReflectsInState() {
        val vm = viewModel(engineAvailable = true)
        vm.generateTestTone()
        assertFalse(vm.state.value.hasWet)

        vm.processWithPreset()

        val s = vm.state.value
        assertFalse(s.processing)
        assertNotNull(s.wetWaveform)
        assertTrue(s.hasWet)
        // Gain of 2 raises the wet RMS above the dry RMS.
        assertTrue(s.wetWaveform!!.rmsDbfs > s.dryWaveform!!.rmsDbfs + 3f)
    }

    @Test
    fun processIsBlockedWhenEngineUnavailable() {
        val vm = viewModel(engineAvailable = false)
        vm.generateTestTone()
        vm.processWithPreset()
        val s = vm.state.value
        assertFalse(s.hasWet)
        assertNotNull(s.message)
        assertTrue(s.message!!.contains("unavailable"))
    }

    @Test
    fun realtimeRequiresPermissionAndHeadphones() {
        val vm = viewModel()
        vm.onPermissionResult(true)

        // No headphone confirmation yet -> blocked with a message.
        vm.startRealtime()
        assertEquals(RealtimeState.OFF, vm.state.value.realtime)
        assertNotNull(vm.state.value.message)

        vm.confirmHeadphones(true)
        assertTrue(vm.state.value.headphonesConfirmed)
    }

    @Test
    fun clearMessageResetsMessage() {
        val vm = viewModel()
        vm.onPermissionResult(false)
        assertNotNull(vm.state.value.message)
        vm.clearMessage()
        assertNull(vm.state.value.message)
    }
}
