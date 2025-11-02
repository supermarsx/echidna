package com.echidna.app.model

data class TelemetrySample(
    val timestampNs: Long,
    val durationUs: Int,
    val cpuUs: Int,
    val flags: Int,
    val xruns: Int
)

data class HookTelemetry(
    val name: String,
    val attempts: Int,
    val successes: Int,
    val failures: Int,
    val lastAttemptNs: Long,
    val lastSuccessNs: Long
)

data class TelemetrySnapshot(
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
    val warnings: List<String>,
    val samples: List<TelemetrySample>,
    val hooks: List<HookTelemetry>
)

data class LatencyBucket(val label: String, val count: Int)

data class CpuHeatPoint(val index: Int, val cpuPercent: Float)

data class TunerState(
    val detectedNote: String,
    val centsOff: Float,
    val detectedHz: Float,
    val targetHz: Float,
    val confidence: Float
)

data class FormantState(
    val shiftCents: Float,
    val width: Float
)

const val TELEMETRY_FLAG_CALLBACK = 1 shl 0
const val TELEMETRY_FLAG_DSP = 1 shl 1
const val TELEMETRY_FLAG_BYPASSED = 1 shl 2
const val TELEMETRY_FLAG_ERROR = 1 shl 3
