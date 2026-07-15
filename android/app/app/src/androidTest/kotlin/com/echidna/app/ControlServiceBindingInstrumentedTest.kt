package com.echidna.app

import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.ServiceTestRule
import com.echidna.control.service.EchidnaControlService
import com.echidna.control.service.IEchidnaControlService
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Service-binding smoke test: the in-APK [EchidnaControlService] resolves via its
 * [ComponentName], the AIDL binder is reachable, and the control/whitelist round-trips
 * behave. Proves the topology from t2-e6 (control service hosted inside the app) works
 * on a real device rather than only static-compiling.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ControlServiceBindingInstrumentedTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    private fun bind(): IEchidnaControlService {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent().apply {
            component = ComponentName(context, EchidnaControlService::class.java)
        }
        val binder = serviceRule.bindService(intent)
        assertNotNull("service binder must be non-null", binder)
        return IEchidnaControlService.Stub.asInterface(binder)
    }

    @Test
    fun bindsAndReadsControlState() {
        val service = bind()
        val json = JSONObject(service.controlState)
        // Schema §4 must be present.
        assertTrue(json.has("masterEnabled"))
        assertTrue(json.has("bypass"))
        assertTrue(json.has("panicUntilEpochMs"))
    }

    @Test
    fun masterEnabledRoundTripsThroughBinder() {
        val service = bind()
        service.setMasterEnabled(false)
        service.setBypass(false)
        val off = JSONObject(service.controlState)
        assertFalse(
            "master must reflect the setMasterEnabled(false) write",
            off.getBoolean("masterEnabled"),
        )

        service.setMasterEnabled(true)
        val on = JSONObject(service.controlState)
        assertTrue(on.getBoolean("masterEnabled"))
    }

    @Test
    fun legacyPreprocessorGatePersistsOutsidePolicyUpdates() {
        val service = bind()
        assertTrue(service.setLegacyPreprocessorEnabled(true))
        assertTrue(service.isLegacyPreprocessorEnabled)

        service.setMasterEnabled(false)
        service.setMasterEnabled(true)
        assertTrue("full control mutations must not erase the separate gate", service.isLegacyPreprocessorEnabled)

        assertTrue(service.setLegacyPreprocessorEnabled(false))
        assertFalse(service.isLegacyPreprocessorEnabled)
    }

    @Test
    fun panicPreservesBaseControlsAndSetsExpiry() {
        val service = bind()
        service.setMasterEnabled(true)
        service.setBypass(false)
        service.triggerPanic(60_000L)
        val json = JSONObject(service.controlState)
        assertTrue("panic preserves master", json.getBoolean("masterEnabled"))
        assertFalse("panic preserves bypass", json.getBoolean("bypass"))
        assertTrue("panic records a future expiry", json.getLong("panicUntilEpochMs") > 0L)
        // Reset so sibling tests start from a clean control state.
        service.triggerPanic(0L)
    }

    @Test
    fun whitelistWriteIsReadBack() {
        val service = bind()
        val pkg = "com.echidna.it.sample"
        service.updateWhitelist(pkg, true)
        val bindings = JSONObject(service.whitelistBindings)
        assertTrue(bindings.has("whitelist"))
        assertTrue(bindings.getJSONObject("whitelist").optBoolean(pkg, false))
    }

    @Test
    fun pushedProfileAppearsInListing() {
        val service = bind()
        val id = "it-profile"
        val json = """
            {
              "name": "IT",
              "engine": {"latencyMode": "LL", "blockMs": 10},
              "modules": [{"id": "mix", "wet": 50}]
            }
        """.trimIndent()
        service.pushProfileSnapshot(id, json)
        val listed = service.listProfiles().toList()
        assertTrue("pushed profile must be listed, got $listed", listed.contains(id))
    }

    @Test
    fun moduleStatusIsWellFormed() {
        val service = bind()
        // getModuleStatus() combines real probes; it must always return schema §3 JSON,
        // even on an unrooted device where no Magisk module is installed.
        val json = JSONObject(service.moduleStatus)
        assertTrue(json.has("selinuxState"))
        assertTrue(json.has("cpu"))
        assertTrue(json.has("audioStack"))
        assertTrue(json.getJSONObject("cpu").has("primaryAbi"))
        assertTrue(json.getJSONObject("cpu").has("nativeHooksSupported"))
        val audioStack = json.getJSONObject("audioStack")
        assertTrue(audioStack.has("hal"))
        assertTrue(audioStack.has("vendorFamily"))
        assertTrue(audioStack.has("openSlEsAvailable"))
        assertTrue(audioStack.has("audioFlingerClientAvailable"))
    }

    @Test
    fun processBlockAppliesPresetWhenNativeEngineIsAvailable() {
        val service = bind()
        service.setProfile(NATIVE_CUT_PRESET_JSON)

        val input = oneKhzTone(frames = PROCESS_FRAMES)
        val output = FloatArray(input.size) { Float.NaN }
        val result = service.processBlock(
            input,
            output,
            PROCESS_FRAMES,
            PROCESS_SAMPLE_RATE,
            PROCESS_CHANNELS,
        )

        assumeTrue(
            "libechidna.so and libech_dsp.so must be installed or discoverable; " +
                "unrooted app-only runs return ECHIDNA_RESULT_NOT_AVAILABLE",
            result != RESULT_NOT_AVAILABLE,
        )
        assertEquals("processBlock should succeed once native is reachable", RESULT_OK, result)

        val ratio = rms(output) / rms(input)
        assertTrue("processed output must contain finite samples", output.all { it.isFinite() })
        assertTrue("cut preset should attenuate output; ratio=$ratio", ratio in 0.30f..0.42f)
        assertTrue(
            "native processBlock should measurably change the block",
            maxAbsDiff(input, output) > 0.05f,
        )
    }

    private fun oneKhzTone(frames: Int): FloatArray =
        FloatArray(frames * PROCESS_CHANNELS) { index ->
            val phase = 2.0 * PI * 1_000.0 * index.toDouble() / PROCESS_SAMPLE_RATE.toDouble()
            (0.2 * sin(phase)).toFloat()
        }

    private fun rms(samples: FloatArray): Float {
        var sumSquares = 0.0
        for (sample in samples) {
            sumSquares += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sumSquares / samples.size.toDouble()).toFloat()
    }

    private fun maxAbsDiff(left: FloatArray, right: FloatArray): Float {
        var max = 0.0f
        for (index in left.indices) {
            max = maxOf(max, abs(left[index] - right[index]))
        }
        return max
    }

    private companion object {
        private const val RESULT_OK = 0
        private const val RESULT_NOT_AVAILABLE = -7
        private const val PROCESS_FRAMES = 4_096
        private const val PROCESS_SAMPLE_RATE = 48_000
        private const val PROCESS_CHANNELS = 1

        private const val NATIVE_CUT_PRESET_JSON = """
            {
              "name": "IT Native Cut",
              "engine": {"latencyMode": "Balanced", "blockMs": 20},
              "modules": [
                {"id": "gate", "enabled": false},
                {"id": "eq", "enabled": false, "bands": []},
                {"id": "comp", "enabled": false},
                {"id": "pitch", "enabled": false},
                {"id": "formant", "enabled": false},
                {"id": "autotune", "enabled": false},
                {"id": "reverb", "enabled": false},
                {"id": "mix", "wet": 100.0, "outGain": -9.0}
              ]
            }
        """
    }
}
