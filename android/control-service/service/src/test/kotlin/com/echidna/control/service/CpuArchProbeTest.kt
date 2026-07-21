package com.echidna.control.service

import android.os.Build
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

/**
 * Pins the ABI -> Zygisk-payload derivation. The probe is the only thing standing between an
 * unsupported process ABI and the compatibility wizard telling a user hooks will work, so every
 * assertion here is about what the probe CLAIMS, not merely that it ran.
 */
@RunWith(RobolectricTestRunner::class)
class CpuArchProbeTest {
    private lateinit var originalAbis: Array<String>

    @Before
    fun captureBuildFields() {
        originalAbis = Build.SUPPORTED_ABIS
    }

    @After
    fun restoreBuildFields() {
        setAbis(*originalAbis)
    }

    @Test
    fun arm64IsTheOnlyArmAbiThatClaimsNativeHookSupport() {
        val arm64 = probeWith("arm64-v8a", "armeabi-v7a", "armeabi")

        assertEquals("arm64-v8a", arm64.getString("primaryAbi"))
        assertEquals("arm64-v8a", arm64.getString("zygiskAbi"))
        assertEquals("AArch64", arm64.getString("cpuFamily"))
        assertTrue(arm64.getBoolean("is64Bit"))
        assertTrue(arm64.getBoolean("moduleSupported"))
        assertTrue(arm64.getBoolean("nativeHooksSupported"))
        assertEquals("native_hooks_supported", arm64.getString("supportLevel"))
        assertEquals(
            "Native inline hooks are implemented for this process ABI.",
            arm64.getString("message"),
        )
        assertEquals(
            listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
            arm64.getJSONArray("supportedAbis").toStringList(),
        )
    }

    @Test
    fun thirtyTwoBitArmLoadsTheModuleButReportsHooksDisabledFailClosed() {
        val armv7 = probeWith("armeabi-v7a", "armeabi")

        assertEquals("armeabi-v7a", armv7.getString("zygiskAbi"))
        assertEquals("ARMv7", armv7.getString("cpuFamily"))
        assertFalse(armv7.getBoolean("is64Bit"))
        // The module ships an armeabi-v7a payload, so it loads...
        assertTrue(armv7.getBoolean("moduleSupported"))
        // ...but the trampoline is not implemented there, and the probe must say so.
        assertFalse(armv7.getBoolean("nativeHooksSupported"))
        assertEquals("module_loads_hooks_disabled", armv7.getString("supportLevel"))
        assertEquals(
            "The module can load for 32-bit ARM, but audio hooks are disabled fail-closed.",
            armv7.getString("message"),
        )
    }

    @Test
    fun legacyArmeabiIsFoldedOntoTheArmeabiV7aPayload() {
        val armeabi = probeWith("armeabi")

        // "armeabi" is not itself a shipped payload name; it must map onto armeabi-v7a rather
        // than fall through to "unsupported".
        assertEquals("armeabi", armeabi.getString("primaryAbi"))
        assertEquals("armeabi-v7a", armeabi.getString("zygiskAbi"))
        assertEquals("ARMv7", armeabi.getString("cpuFamily"))
        assertTrue(armeabi.getBoolean("moduleSupported"))
        assertFalse(armeabi.getBoolean("nativeHooksSupported"))
        assertEquals("module_loads_hooks_disabled", armeabi.getString("supportLevel"))
    }

    @Test
    fun x86_64IsHookSupportedWhileThirtyTwoBitX86IsNotShippedAtAll() {
        val x64 = probeWith("x86_64", "x86")
        assertEquals("x86_64", x64.getString("zygiskAbi"))
        assertEquals("x86_64", x64.getString("cpuFamily"))
        assertTrue(x64.getBoolean("is64Bit"))
        assertTrue(x64.getBoolean("nativeHooksSupported"))
        assertEquals("native_hooks_supported", x64.getString("supportLevel"))

        val x86 = probeWith("x86")
        assertEquals("x86", x86.getString("zygiskAbi"))
        assertEquals("x86", x86.getString("cpuFamily"))
        assertFalse(x86.getBoolean("is64Bit"))
        assertFalse(x86.getBoolean("moduleSupported"))
        assertFalse(x86.getBoolean("nativeHooksSupported"))
        assertEquals("unsupported", x86.getString("supportLevel"))
        assertEquals(
            "Echidna does not ship a Zygisk payload for this process ABI.",
            x86.getString("message"),
        )
    }

    @Test
    fun anUnrecognisedAbiIsReportedUnsupportedRatherThanGuessed() {
        val exotic = probeWith("riscv64")

        assertEquals("riscv64", exotic.getString("primaryAbi"))
        // Unknown ABIs pass through unchanged instead of being coerced onto a shipped payload.
        assertEquals("riscv64", exotic.getString("zygiskAbi"))
        assertEquals("riscv64", exotic.getString("cpuFamily"))
        assertFalse(exotic.getBoolean("is64Bit"))
        assertFalse(exotic.getBoolean("moduleSupported"))
        assertFalse(exotic.getBoolean("nativeHooksSupported"))
        assertEquals("unsupported", exotic.getString("supportLevel"))
        assertEquals(
            "Echidna does not ship a Zygisk payload for this process ABI.",
            exotic.getString("message"),
        )
    }

    @Test
    fun anEmptyAbiListIsReportedAsAndroidHavingSaidNothing() {
        val none = probeWith()

        assertEquals("", none.getString("primaryAbi"))
        assertEquals(0, none.getJSONArray("supportedAbis").length())
        assertEquals("", none.getString("zygiskAbi"))
        assertEquals("Unknown", none.getString("cpuFamily"))
        assertFalse(none.getBoolean("is64Bit"))
        assertFalse(none.getBoolean("moduleSupported"))
        assertFalse(none.getBoolean("nativeHooksSupported"))
        assertEquals("unsupported", none.getString("supportLevel"))
        // Distinct from the "we ship nothing for this ABI" message: nothing was reported at all.
        assertEquals(
            "No process ABI was reported by Android.",
            none.getString("message"),
        )
    }

    private fun probeWith(vararg abis: String): JSONObject {
        setAbis(*abis)
        return CpuArchProbe().probe()
    }

    private fun setAbis(vararg abis: String) {
        ReflectionHelpers.setStaticField(
            Build::class.java,
            "SUPPORTED_ABIS",
            arrayOf(*abis),
        )
    }

    private fun org.json.JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }
}
