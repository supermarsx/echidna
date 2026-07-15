package com.echidna.app.data

import com.echidna.app.model.DspEngineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parsing tests for the control-service status contracts consumed by the app:
 * combined module status (t2-e6-signatures §3), global control state (§4), and the
 * whitelist/app-binding read-back (§2). Robolectric supplies a real `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StatusJsonParsingTest {

    @Test
    fun `parseModuleStatus reads the full status schema`() {
        val json = """
            {
              "magiskModuleInstalled": true,
              "zygiskEnabled": true,
              "selinuxState": "ENFORCING",
              "selinuxStatus": "Enforcing (policy and capture route unverified)",
              "policyToolAvailable": true,
              "policyAppliedVerified": false,
              "nativeRouteVerified": false,
              "javaFallbackRecommended": true,
              "cpu": {
                "primaryAbi": "arm64-v8a",
                "supportedAbis": ["arm64-v8a", "armeabi-v7a"],
                "cpuFamily": "AArch64",
                "is64Bit": true,
                "zygiskAbi": "arm64-v8a",
                "moduleSupported": true,
                "nativeHooksSupported": true,
                "supportLevel": "native_hooks_supported",
                "message": "Native inline hooks are implemented for this process ABI."
              },
              "audioStack": {
                "hal": "Qualcomm (kona)",
                "manufacturer": "Qualcomm",
                "boardPlatform": "kona",
                "vendorFamily": "Qualcomm",
                "aaudioSupported": true,
                "openSlEsAvailable": true,
                "audioFlingerClientAvailable": true,
                "tinyAlsaAvailable": false,
                "lowLatency": true,
                "proAudio": false,
                "sampleRate": 48000,
                "framesPerBuffer": 240
              },
              "notes": "all good",
              "lastError": "prior glitch"
            }
        """.trimIndent()
        val status = TelemetryParser.parseModuleStatus(json)
        assertNotNull(status)
        status!!
        assertTrue(status.magiskModuleInstalled)
        assertTrue(status.zygiskEnabled)
        assertEquals("ENFORCING", status.selinuxState)
        assertEquals("Enforcing (policy and capture route unverified)", status.selinuxStatus)
        assertTrue(status.policyToolAvailable)
        assertFalse(status.policyAppliedVerified)
        assertFalse(status.nativeRouteVerified)
        assertTrue(status.javaFallbackRecommended)
        assertEquals("arm64-v8a", status.cpu.primaryAbi)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), status.cpu.supportedAbis)
        assertEquals("AArch64", status.cpu.cpuFamily)
        assertTrue(status.cpu.is64Bit)
        assertEquals("arm64-v8a", status.cpu.zygiskAbi)
        assertTrue(status.cpu.moduleSupported)
        assertTrue(status.cpu.nativeHooksSupported)
        assertEquals("native_hooks_supported", status.cpu.supportLevel)
        assertEquals("Qualcomm (kona)", status.audioStack.hal)
        assertEquals("Qualcomm", status.audioStack.manufacturer)
        assertEquals("kona", status.audioStack.boardPlatform)
        assertEquals("Qualcomm", status.audioStack.vendorFamily)
        assertTrue(status.audioStack.aaudioSupported)
        assertTrue(status.audioStack.openSlEsAvailable)
        assertTrue(status.audioStack.audioFlingerClientAvailable)
        assertFalse(status.audioStack.tinyAlsaAvailable)
        assertEquals(48000, status.audioStack.sampleRate)
        assertEquals(240, status.audioStack.framesPerBuffer)
        assertEquals("all good", status.notes)
        assertEquals("prior glitch", status.lastError)
    }

    @Test
    fun `parseModuleStatus applies documented defaults for sparse json`() {
        val status = TelemetryParser.parseModuleStatus("""{"magiskModuleInstalled": false}""")
        assertNotNull(status)
        status!!
        assertFalse(status.zygiskEnabled)
        assertEquals("UNKNOWN", status.selinuxState)
        assertEquals("Unknown", status.selinuxStatus)
        assertEquals("Unknown", status.cpu.cpuFamily)
        assertEquals("unknown", status.cpu.supportLevel)
        assertTrue(status.cpu.supportedAbis.isEmpty())
        assertFalse(status.cpu.nativeHooksSupported)
        assertEquals(0, status.audioStack.sampleRate)
        assertEquals("", status.audioStack.hal)
        assertEquals("Unknown", status.audioStack.vendorFamily)
        assertFalse(status.audioStack.openSlEsAvailable)
        assertNull(status.notes)
        assertNull(status.lastError)
    }

    @Test
    fun `parseControlState reads the control schema`() {
        val json = """
            {"masterEnabled": false, "bypass": true, "panicUntilEpochMs": 12345,
             "sidetoneEnabled": true, "sidetoneGainDb": -18.5,
             "engineMode": "compatibility"}
        """.trimIndent()
        val state = TelemetryParser.parseControlState(json)
        assertNotNull(state)
        state!!
        assertFalse(state.masterEnabled)
        assertTrue(state.bypass)
        assertEquals(12345L, state.panicUntilEpochMs)
        assertTrue(state.sidetoneEnabled)
        assertEquals(-18.5f, state.sidetoneGainDb, 1e-3f)
        assertEquals(DspEngineMode.COMPATIBILITY, state.engineMode)
    }

    @Test
    fun `parseControlState defaults masterEnabled to true when absent`() {
        val state = TelemetryParser.parseControlState("{}")
        assertNotNull(state)
        assertTrue(state!!.masterEnabled)
        assertFalse(state.bypass)
        assertEquals(0L, state.panicUntilEpochMs)
        assertEquals(DspEngineMode.NATIVE_FIRST, state.engineMode)
    }

    @Test
    fun `parseWhitelistBindings splits whitelist and app bindings`() {
        val json = """
            {"whitelist": {"com.foo.app": true, "com.bar.app": false},
             "appBindings": {"com.foo.app": "preset-123"}}
        """.trimIndent()
        val bindings = TelemetryParser.parseWhitelistBindings(json)
        assertNotNull(bindings)
        bindings!!
        assertEquals(true, bindings.whitelist["com.foo.app"])
        assertEquals(false, bindings.whitelist["com.bar.app"])
        assertEquals("preset-123", bindings.appBindings["com.foo.app"])
        assertNull(bindings.appBindings["com.bar.app"])
    }

    @Test
    fun `parseWhitelistBindings tolerates an empty document`() {
        val bindings = TelemetryParser.parseWhitelistBindings("{}")
        assertNotNull(bindings)
        assertTrue(bindings!!.whitelist.isEmpty())
        assertTrue(bindings.appBindings.isEmpty())
    }

    @Test
    fun `parsers reject blank and malformed input`() {
        assertNull(TelemetryParser.parseModuleStatus(""))
        assertNull(TelemetryParser.parseModuleStatus("   "))
        assertNull(TelemetryParser.parseModuleStatus("nonsense"))
        assertNull(TelemetryParser.parseControlState(""))
        assertNull(TelemetryParser.parseControlState("["))
        assertNull(TelemetryParser.parseWhitelistBindings(""))
        assertNull(TelemetryParser.parseWhitelistBindings("}{"))
    }
}
