package com.echidna.control.service

import android.content.pm.PackageManager
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Pins the vendor/HAL classification the compatibility wizard reads. The class exists because the
 * app used to fabricate "Qualcomm QSSI"; these tests exist so it cannot start fabricating again —
 * every field is checked against the Build inputs that produced it.
 */
@RunWith(RobolectricTestRunner::class)
class AudioStackProbeTest {
    private lateinit var originalBoard: String
    private lateinit var originalHardware: String
    private lateinit var originalManufacturer: String

    @Before
    fun captureBuildFields() {
        originalBoard = Build.BOARD
        originalHardware = Build.HARDWARE
        originalManufacturer = Build.MANUFACTURER
    }

    @After
    fun restoreBuildFields() {
        setBuild(originalManufacturer, originalBoard, originalHardware)
    }

    @Test
    fun qualcommIsRecognisedFromTheBareSocNumberNotJustTheVendorWord() {
        // The device never says "Qualcomm" anywhere; only the SoC model number is present.
        val bySocNumber = probeWith(manufacturer = "Xiaomi", board = "sm8550", hardware = "qcom")
        assertEquals("Qualcomm", bySocNumber.getString("vendorFamily"))

        val byCodename = probeWith(manufacturer = "OnePlus", board = "lahaina", hardware = "unknown")
        assertEquals("Qualcomm", byCodename.getString("vendorFamily"))

        // "sm" followed by too few digits is not an SoC model and must not match.
        val notASoc = probeWith(manufacturer = "Acme", board = "sm55", hardware = "acme")
        assertEquals("Other / unclassified", notASoc.getString("vendorFamily"))
    }

    @Test
    fun mediatekMatchesTheMtSocPatternAndNotArbitraryMtPrefixes() {
        val byPattern = probeWith(manufacturer = "Realme", board = "mt6893", hardware = "mt6893")
        assertEquals("MediaTek", byPattern.getString("vendorFamily"))

        // Exactly four digits are required; "mt68931" is not an MT part number.
        val tooManyDigits = probeWith(manufacturer = "Acme", board = "mt68931", hardware = "acme")
        assertEquals("Other / unclassified", tooManyDigits.getString("vendorFamily"))
    }

    @Test
    fun emulatorWinsOverEveryOtherSignalSoTestRunsAreNeverReportedAsRealSilicon() {
        // goldfish/ranchu devices also report a Google/Android manufacturer; the emulator branch
        // is checked first precisely so an emulator is never classified as shipping silicon.
        val ranchu = probeWith(manufacturer = "Google", board = "goldfish_x86_64", hardware = "ranchu")
        assertEquals("Android Emulator", ranchu.getString("vendorFamily"))
    }

    @Test
    fun exynosAndTensorAreDistinguishedFromThePlainSamsungFallback() {
        assertEquals(
            "Samsung Exynos",
            probeWith("samsung", "exynos2200", "s5e9925").getString("vendorFamily"),
        )
        assertEquals(
            "Google Tensor",
            probeWith("Google", "zuma", "zuma").getString("vendorFamily"),
        )
        assertEquals(
            "Google Tensor",
            probeWith("Google", "gs101", "gs101").getString("vendorFamily"),
        )
        // A Samsung device on no recognised platform still resolves to the vendor, not "Other".
        assertEquals(
            "Samsung",
            probeWith("Samsung", "universal9611", "universal9611").getString("vendorFamily"),
        )
    }

    @Test
    fun anAbsentBoardPlatformIsReportedUnknownRatherThanGuessed() {
        val blank = probeWith(manufacturer = "Acme", board = "", hardware = "")

        assertEquals("Unknown", blank.getString("vendorFamily"))
        assertEquals("", blank.getString("boardPlatform"))
        // With no platform the HAL description degrades to the manufacturer alone — no
        // parenthesised empty platform, and no invented platform name.
        assertEquals("Acme", blank.getString("hal"))
        assertEquals("Acme", blank.getString("manufacturer"))
    }

    @Test
    fun halDescriptionCombinesManufacturerAndPlatformWithTheManufacturerCapitalised() {
        val probe = probeWith(manufacturer = "xiaomi", board = "sm8550", hardware = "qcom")

        assertEquals("Xiaomi (sm8550)", probe.getString("hal"))
        // The raw manufacturer is reported unchanged alongside the prettified HAL string.
        assertEquals("xiaomi", probe.getString("manufacturer"))
        assertEquals("sm8550", probe.getString("boardPlatform"))
    }

    @Test
    fun lowLatencyAndProAudioMirrorTheDeclaredSystemFeaturesRatherThanADefault() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager)
            .setSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY, true)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_AUDIO_PRO, false)

        val probe = probeWith(manufacturer = "Acme", board = "acme", hardware = "acme")

        assertTrue(probe.getBoolean("lowLatency"))
        assertFalse(probe.getBoolean("proAudio"))
    }

    @Test
    fun unknownNumericAudioPropertiesResolveToZeroInsteadOfBeingOmittedOrFaked() {
        // No AudioManager output properties are declared by the platform image, which is exactly
        // the case the doc comment promises resolves to 0 rather than to a plausible-looking
        // 48000/256 pair.
        val probe = probeWith(manufacturer = "Acme", board = "acme", hardware = "acme")

        assertEquals(0, probe.getInt("sampleRate"))
        assertEquals(0, probe.getInt("framesPerBuffer"))
    }

    @Test
    @Config(sdk = [26])
    fun aaudioIsReportedSupportedFromOreoOnwards() {
        // AAudio landed in O; the probe derives this from SDK_INT and must not hard-code it.
        assertTrue(
            probeWith("Acme", "acme", "acme").getBoolean("aaudioSupported"),
        )
    }

    @Test
    fun systemLibraryProbesReportAbsenceOnAHostWithNoAndroidSystemImage() {
        // /system/lib* does not exist on the build host, so every library probe must report
        // false. A probe that returned true here would be reporting a library it never saw.
        val probe = probeWith("Acme", "acme", "acme")

        assertFalse(probe.getBoolean("openSlEsAvailable"))
        assertFalse(probe.getBoolean("audioFlingerClientAvailable"))
        assertFalse(probe.getBoolean("tinyAlsaAvailable"))
    }

    private fun probeWith(manufacturer: String, board: String, hardware: String): JSONObject {
        setBuild(manufacturer, board, hardware)
        return AudioStackProbe(RuntimeEnvironment.getApplication()).probe()
    }

    private fun setBuild(manufacturer: String, board: String, hardware: String) {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", manufacturer)
        ReflectionHelpers.setStaticField(Build::class.java, "BOARD", board)
        ReflectionHelpers.setStaticField(Build::class.java, "HARDWARE", hardware)
    }
}
