package com.echidna.control.service

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
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
        val json = JSONObject()
        json.put("hal", describeHal())
        json.put("aaudioSupported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        json.put("lowLatency", hasFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY))
        json.put("proAudio", hasFeature(PackageManager.FEATURE_AUDIO_PRO))
        json.put("sampleRate", intProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE))
        json.put("framesPerBuffer", intProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER))
        return json
    }

    private fun describeHal(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val platform = systemProperty("ro.board.platform").ifBlank { Build.BOARD }
        return if (platform.isBlank()) manufacturer else "$manufacturer ($platform)"
    }

    private fun hasFeature(feature: String): Boolean =
        appContext.packageManager.hasSystemFeature(feature)

    private fun intProperty(key: String): Int {
        val raw = audioManager?.getProperty(key) ?: return 0
        return raw.toIntOrNull() ?: 0
    }

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
}
