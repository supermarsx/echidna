package com.echidna.app.data

import com.echidna.app.model.HookTelemetry
import com.echidna.app.model.TelemetrySample
import com.echidna.app.model.TelemetrySnapshot
import org.json.JSONArray
import org.json.JSONObject

internal object TelemetryParser {
    fun parse(json: String): TelemetrySnapshot? {
        if (json.isBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val totalCallbacks = root.optLong("totalCallbacks")
        val averageLatency = root.optDouble("averageLatencyMs", 0.0).toFloat()
        val averageCpu = root.optDouble("averageCpuPercent", 0.0).toFloat()
        val inputRms = root.optDouble("inputRms", -120.0).toFloat()
        val outputRms = root.optDouble("outputRms", -120.0).toFloat()
        val inputPeak = root.optDouble("inputPeak", -120.0).toFloat()
        val outputPeak = root.optDouble("outputPeak", -120.0).toFloat()
        val detectedPitch = root.optDouble("detectedPitchHz", 0.0).toFloat()
        val targetPitch = root.optDouble("targetPitchHz", 0.0).toFloat()
        val formantShift = root.optDouble("formantShiftCents", 0.0).toFloat()
        val formantWidth = root.optDouble("formantWidth", 0.0).toFloat()
        val xruns = root.optInt("xruns", 0)
        val warningFlags = root.optInt("warningFlags", 0)
        val warnings = mutableListOf<String>()
        if (warningFlags and (1 shl 0) != 0) warnings += "Latency exceeded guard threshold"
        if (warningFlags and (1 shl 1) != 0) warnings += "CPU usage exceeded 75%"
        if (warningFlags and (1 shl 2) != 0) warnings += "XRuns detected"

        val samples = parseSamples(root.optJSONArray("samples"))
        val hooks = parseHooks(root.optJSONArray("hooks"))

        return TelemetrySnapshot(
            totalCallbacks = totalCallbacks,
            averageLatencyMs = averageLatency,
            averageCpuPercent = averageCpu,
            inputRms = inputRms,
            outputRms = outputRms,
            inputPeak = inputPeak,
            outputPeak = outputPeak,
            detectedPitchHz = detectedPitch,
            targetPitchHz = targetPitch,
            formantShiftCents = formantShift,
            formantWidth = formantWidth,
            xruns = xruns,
            warnings = warnings,
            samples = samples,
            hooks = hooks
        )
    }

    private fun parseSamples(array: JSONArray?): List<TelemetrySample> {
        if (array == null) return emptyList()
        val list = ArrayList<TelemetrySample>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            list += TelemetrySample(
                timestampNs = obj.optLong("timestampNs"),
                durationUs = obj.optInt("durationUs"),
                cpuUs = obj.optInt("cpuUs"),
                flags = obj.optInt("flags"),
                xruns = obj.optInt("xruns")
            )
        }
        return list
    }

    private fun parseHooks(array: JSONArray?): List<HookTelemetry> {
        if (array == null) return emptyList()
        val list = ArrayList<HookTelemetry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            list += HookTelemetry(
                name = name,
                library = obj.optString("library"),
                symbol = obj.optString("symbol"),
                reason = obj.optString("reason"),
                attempts = obj.optInt("attempts"),
                successes = obj.optInt("successes"),
                failures = obj.optInt("failures"),
                lastAttemptNs = obj.optLong("lastAttemptNs"),
                lastSuccessNs = obj.optLong("lastSuccessNs")
            )
        }
        return list
    }
}
