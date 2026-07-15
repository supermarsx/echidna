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
    val library: String,
    val symbol: String,
    val reason: String,
    val attempts: Int,
    val successes: Int,
    val failures: Int,
    val lastAttemptNs: Long,
    val lastSuccessNs: Long
)

data class RuntimeRouteTelemetry(
    val process: String,
    val route: String,
    val generation: Long,
    val state: String,
    val sequence: Long,
    val ageMs: Long,
    val recentMutation: Boolean,
    val blocks: Long,
    val frames: Long,
    val failures: Long,
    val mutations: Long,
    val verification: String = TELEMETRY_VERIFICATION_UNVERIFIED,
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
    val hooks: List<HookTelemetry>,
    val verification: String = "unverified",
    val currentPolicyGeneration: Long = 0L,
    val routes: List<RuntimeRouteTelemetry> = emptyList()
) {
    val hasVerifiedRuntimeTelemetry: Boolean
        get() = currentPolicyGeneration > 0L && routes.any {
            it.generation == currentPolicyGeneration &&
                it.verification in TELEMETRY_PROCESSING_PROOF_VERIFICATIONS
        }

    val isVerifiedProcessing: Boolean
        get() = hasVerifiedRuntimeTelemetry && routes.any { route ->
            route.generation == currentPolicyGeneration &&
                route.verification in TELEMETRY_PROCESSING_PROOF_VERIFICATIONS &&
                route.state == "processing" &&
                route.recentMutation &&
                route.mutations > 0L
        }
}

const val TELEMETRY_VERIFICATION_AUTHENTICATED_SOCKET_V2 = "authenticated_socket_v2"
const val TELEMETRY_VERIFICATION_EFFECT_HMAC_V1 = "effect_hmac_v1"
const val TELEMETRY_VERIFICATION_CALLER_ATTESTED_BINDER_V1 = "caller_attested_binder_v1"
const val TELEMETRY_VERIFICATION_MIXED = "mixed_route_verification_v1"
const val TELEMETRY_VERIFICATION_UNVERIFIED = "unverified"
private val TELEMETRY_PROCESSING_PROOF_VERIFICATIONS = setOf(
    TELEMETRY_VERIFICATION_AUTHENTICATED_SOCKET_V2,
    TELEMETRY_VERIFICATION_EFFECT_HMAC_V1,
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
