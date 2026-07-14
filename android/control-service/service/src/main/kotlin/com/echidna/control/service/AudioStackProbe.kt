package com.echidna.control.service

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import java.io.File
import org.json.JSONObject

private const val PROBE_TAG = "EchidnaAudioProbe"

/**
 * Probes the real device audio stack so the companion app's compatibility wizard and
 * engine-status surfaces stop relying on fabricated "Qualcomm QSSI"/"Enforcing" fallbacks.
 * All values come from [Build], [AudioManager] low-latency properties and package features
 * — no fabricated constants. Unknown numeric fields resolve to 0.
 */
class AudioStackProbe(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun probe(): JSONObject {
        val platform = systemProperty("ro.board.platform").ifBlank { Build.BOARD }
        val json = JSONObject()
        json.put("hal", describeHal(platform))
        json.put("manufacturer", Build.MANUFACTURER)
        json.put("boardPlatform", platform)
        json.put("vendorFamily", vendorFamily(Build.MANUFACTURER, platform))
        json.put("aaudioSupported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        json.put("openSlEsAvailable", anySystemLibrary("libOpenSLES.so"))
        json.put("audioFlingerClientAvailable", anySystemLibrary("libaudioclient.so"))
        json.put("tinyAlsaAvailable", anySystemLibrary("libtinyalsa.so"))
        json.put("lowLatency", hasFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY))
        json.put("proAudio", hasFeature(PackageManager.FEATURE_AUDIO_PRO))
        json.put("sampleRate", intProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE))
        json.put("framesPerBuffer", intProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER))
        return json
    }

    private fun describeHal(platform: String): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return if (platform.isBlank()) manufacturer else "$manufacturer ($platform)"
    }

    private fun vendorFamily(manufacturer: String, platform: String): String {
        val text = "${manufacturer.lowercase()} ${platform.lowercase()} ${Build.HARDWARE.lowercase()}"
        return when {
            text.contains("ranchu") || text.contains("goldfish") -> "Android Emulator"
            text.contains("qcom") ||
                text.contains("qualcomm") ||
                text.contains("msm") ||
                text.contains("sdm") ||
                text.contains("kona") ||
                text.contains("lahaina") ||
                text.contains("taro") ||
                text.contains("kalama") ||
                Regex("""\bsm[0-9]{3,5}\b""").containsMatchIn(text) -> "Qualcomm"
            text.contains("mediatek") || Regex("""\bmt[0-9]{4}\b""").containsMatchIn(text) ->
                "MediaTek"
            text.contains("exynos") -> "Samsung Exynos"
            text.contains("tensor") ||
                text.contains("gs101") ||
                text.contains("gs201") ||
                text.contains("zuma") -> "Google Tensor"
            manufacturer.equals("samsung", ignoreCase = true) -> "Samsung"
            platform.isBlank() -> "Unknown"
            else -> "Other / unclassified"
        }
    }

    private fun hasFeature(feature: String): Boolean =
        appContext.packageManager.hasSystemFeature(feature)

    private fun intProperty(key: String): Int {
        val raw = audioManager?.getProperty(key) ?: return 0
        return raw.toIntOrNull() ?: 0
    }

    private fun anySystemLibrary(name: String): Boolean =
        libraryDirs.any { dir -> File(dir, name).isFile }

    private fun systemProperty(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getter = clazz.getMethod("get", String::class.java)
            (getter.invoke(null, key) as? String).orEmpty()
        } catch (ex: ReflectiveOperationException) {
            Log.d(PROBE_TAG, "SystemProperties unavailable for $key", ex)
            ""
        }
    }

    private companion object {
        val libraryDirs = listOf(
            "/system/lib64",
            "/system/lib",
            "/system_ext/lib64",
            "/system_ext/lib",
            "/vendor/lib64",
            "/vendor/lib",
            "/apex/com.android.media/lib64",
            "/apex/com.android.media/lib"
        )
    }
}
