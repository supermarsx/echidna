package com.echidna.control.service

import org.json.JSONArray
import org.json.JSONObject

private const val WARNING_HIGH_LATENCY = 1 shl 0
private const val WARNING_HIGH_CPU = 1 shl 1
private const val WARNING_XRUN = 1 shl 2

internal data class TelemetrySample(
    val timestampNs: Long,
    val durationUs: Int,
    val cpuUs: Int,
    val flags: Int,
    val xruns: Int
)

internal data class HookTelemetry(
    val name: String,
    val attempts: Int,
    val successes: Int,
    val failures: Int,
    val lastAttemptNs: Long,
    val lastSuccessNs: Long
)

internal data class TelemetrySnapshot(
    val totalCallbacks: Long,
    val averageLatencyMs: Float,
    val averageCpuPercent: Float,
    val inputRms: Float,
    val outputRms: Float,
    val inputPeak: Float,
    val outputPeak: Float,
    val detectedPitchHz: Float,
    val targetPitchHz: Float,
    val formantShiftCents: Float,
    val formantWidth: Float,
    val xruns: Int,
    val warningFlags: Int,
    val samples: List<TelemetrySample>,
    val hooks: List<HookTelemetry>
) {
    val warnings: List<String>
        get() {
            val warnings = mutableListOf<String>()
            if (warningFlags and WARNING_HIGH_LATENCY != 0) {
                warnings += "Latency exceeded guard threshold"
            }
            if (warningFlags and WARNING_HIGH_CPU != 0) {
                warnings += "CPU usage exceeded 75%"
            }
            if (warningFlags and WARNING_XRUN != 0) {
                warnings += "XRuns detected"
            }
            return warnings
        }

    fun toJson(includeSamples: Boolean = true): String {
        val root = JSONObject()
        root.put("totalCallbacks", totalCallbacks)
        root.put("averageLatencyMs", averageLatencyMs)
        root.put("averageCpuPercent", averageCpuPercent)
        root.put("inputRms", inputRms)
        root.put("outputRms", outputRms)
        root.put("inputPeak", inputPeak)
        root.put("outputPeak", outputPeak)
        root.put("detectedPitchHz", detectedPitchHz)
        root.put("targetPitchHz", targetPitchHz)
        root.put("formantShiftCents", formantShiftCents)
        root.put("formantWidth", formantWidth)
        root.put("xruns", xruns)
        root.put("warningFlags", warningFlags)
        if (includeSamples) {
            val samplesArray = JSONArray()
            samples.forEach { sample ->
                val obj = JSONObject()
                obj.put("timestampNs", sample.timestampNs)
                obj.put("durationUs", sample.durationUs)
                obj.put("cpuUs", sample.cpuUs)
                obj.put("flags", sample.flags)
                obj.put("xruns", sample.xruns)
                samplesArray.put(obj)
            }
            root.put("samples", samplesArray)
        }
        val hooksArray = JSONArray()
        hooks.forEach { hook ->
            val obj = JSONObject()
            obj.put("name", hook.name)
            obj.put("attempts", hook.attempts)
            obj.put("successes", hook.successes)
            obj.put("failures", hook.failures)
            obj.put("lastAttemptNs", hook.lastAttemptNs)
            obj.put("lastSuccessNs", hook.lastSuccessNs)
            hooksArray.put(obj)
        }
        root.put("hooks", hooksArray)
        return root.toString()
    }

    fun anonymizedJson(): String {
        val root = JSONObject()
        root.put("totalCallbacks", totalCallbacks)
        root.put("averageLatencyMs", averageLatencyMs)
        root.put("averageCpuPercent", averageCpuPercent)
        root.put("warnings", JSONArray(warnings))
        val hooksArray = JSONArray()
        hooks.forEach { hook ->
            val obj = JSONObject()
            obj.put("name", hook.name)
            obj.put("attempts", hook.attempts)
            obj.put("successRate", if (hook.attempts == 0) 0.0 else hook.successes.toDouble() / hook.attempts)
            hooksArray.put(obj)
        }
        root.put("hooks", hooksArray)
        root.put("xruns", xruns)
        return root.toString()
    }
}
