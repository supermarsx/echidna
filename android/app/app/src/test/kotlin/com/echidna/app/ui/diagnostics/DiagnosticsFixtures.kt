package com.echidna.app.ui.diagnostics

import com.echidna.app.model.AudioStackInfo
import com.echidna.app.model.CpuArchInfo
import com.echidna.app.model.DspMetrics
import com.echidna.app.model.HookTelemetry
import com.echidna.app.model.ModuleStatus
import com.echidna.app.model.RuntimeRouteTelemetry
import com.echidna.app.model.TELEMETRY_VERIFICATION_AUTHENTICATED_SOCKET_V2
import com.echidna.app.model.TelemetrySample
import com.echidna.app.model.TelemetrySnapshot

/**
 * Builders for the diagnostics Compose tests. They exist so a test can state only the one thing it
 * is pinning (a hook that installed, a route that is really processing, an audio stack with no
 * reported sample rate) instead of restating a dozen irrelevant fields.
 */

internal fun hookTelemetry(
    name: String,
    attempts: Int = 0,
    successes: Int = 0,
    failures: Int = 0,
    library: String = "",
    symbol: String = "",
    reason: String = "",
) = HookTelemetry(
    name = name,
    library = library,
    symbol = symbol,
    reason = reason,
    attempts = attempts,
    successes = successes,
    failures = failures,
    lastAttemptNs = 0L,
    lastSuccessNs = 0L,
)

/** Generation used by every "verified" snapshot below; any non-zero value works. */
private const val POLICY_GENERATION = 7L

/**
 * A snapshot whose runtime verification is controlled explicitly.
 *
 * [runtimeVerified] adds a current-generation route carrying authenticated-socket verification,
 * which is what `hasVerifiedRuntimeTelemetry` requires; [processing] additionally makes that route
 * report a recent mutation, which is what `isVerifiedProcessing` requires. Leaving both false
 * reproduces the honest "engine not active" device state.
 */
internal fun telemetrySnapshot(
    totalCallbacks: Long = 0L,
    xruns: Int = 0,
    hooks: List<HookTelemetry> = emptyList(),
    samples: List<TelemetrySample> = emptyList(),
    warnings: List<String> = emptyList(),
    runtimeVerified: Boolean = false,
    processing: Boolean = false,
    totalBypasses: Long = 0L,
    totalInstallEvents: Long = 0L,
    totalInstallFailures: Long = 0L,
    anyRouteInstalled: Boolean = false,
    averageLatencyMs: Float = 0f,
    averageCpuPercent: Float = 0f,
): TelemetrySnapshot = TelemetrySnapshot(
    totalCallbacks = totalCallbacks,
    averageLatencyMs = averageLatencyMs,
    averageCpuPercent = averageCpuPercent,
    inputRms = 0f,
    outputRms = 0f,
    inputPeak = 0f,
    outputPeak = 0f,
    detectedPitchHz = 0f,
    targetPitchHz = 0f,
    formantShiftCents = 0f,
    formantWidth = 0f,
    xruns = xruns,
    warnings = warnings,
    samples = samples,
    hooks = hooks,
    verification = TELEMETRY_VERIFICATION_AUTHENTICATED_SOCKET_V2,
    currentPolicyGeneration = if (runtimeVerified) POLICY_GENERATION else 0L,
    routes = if (runtimeVerified) {
        listOf(
            RuntimeRouteTelemetry(
                process = "com.example.target",
                route = "aaudio",
                generation = POLICY_GENERATION,
                state = if (processing) "processing" else "idle",
                sequence = 1L,
                ageMs = 5L,
                recentMutation = processing,
                blocks = totalCallbacks,
                frames = totalCallbacks * 480L,
                failures = 0L,
                mutations = if (processing) 12L else 0L,
                verification = TELEMETRY_VERIFICATION_AUTHENTICATED_SOCKET_V2,
            ),
        )
    } else {
        emptyList()
    },
    totalBypasses = totalBypasses,
    totalInstallEvents = totalInstallEvents,
    totalInstallFailures = totalInstallFailures,
    anyRouteInstalled = anyRouteInstalled,
)

internal fun telemetrySample(durationUs: Int, cpuUs: Int) = TelemetrySample(
    timestampNs = 0L,
    durationUs = durationUs,
    cpuUs = cpuUs,
    flags = 0,
    xruns = 0,
)

internal fun audioStack(
    hal: String = "qcom-audio",
    vendorFamily: String = "Qualcomm",
    sampleRate: Int = 48000,
    framesPerBuffer: Int = 192,
    aaudioSupported: Boolean = true,
    openSlEsAvailable: Boolean = true,
    audioFlingerClientAvailable: Boolean = true,
    tinyAlsaAvailable: Boolean = false,
    lowLatency: Boolean = true,
    proAudio: Boolean = false,
) = AudioStackInfo(
    hal = hal,
    manufacturer = "TestCo",
    boardPlatform = "testboard",
    vendorFamily = vendorFamily,
    aaudioSupported = aaudioSupported,
    openSlEsAvailable = openSlEsAvailable,
    audioFlingerClientAvailable = audioFlingerClientAvailable,
    tinyAlsaAvailable = tinyAlsaAvailable,
    lowLatency = lowLatency,
    proAudio = proAudio,
    sampleRate = sampleRate,
    framesPerBuffer = framesPerBuffer,
)

internal fun cpuArch(
    primaryAbi: String = "arm64-v8a",
    supportedAbis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
    cpuFamily: String = "ARM64",
    zygiskAbi: String = "arm64",
    nativeHooksSupported: Boolean = true,
    message: String = "",
) = CpuArchInfo(
    primaryAbi = primaryAbi,
    supportedAbis = supportedAbis,
    cpuFamily = cpuFamily,
    is64Bit = true,
    zygiskAbi = zygiskAbi,
    moduleSupported = true,
    nativeHooksSupported = nativeHooksSupported,
    supportLevel = "full",
    message = message,
)

internal fun moduleStatus(
    magiskModuleInstalled: Boolean = true,
    zygiskEnabled: Boolean = true,
    selinuxState: String = "policy applied",
    selinuxStatus: String = "Enforcing",
    nativeRouteVerified: Boolean = true,
    javaFallbackRecommended: Boolean = false,
    cpu: CpuArchInfo = cpuArch(),
    stack: AudioStackInfo = audioStack(),
    notes: String? = null,
    lastError: String? = null,
) = ModuleStatus(
    magiskModuleInstalled = magiskModuleInstalled,
    zygiskEnabled = zygiskEnabled,
    selinuxState = selinuxState,
    selinuxStatus = selinuxStatus,
    policyToolAvailable = true,
    policyAppliedVerified = true,
    nativeRouteVerified = nativeRouteVerified,
    javaFallbackRecommended = javaFallbackRecommended,
    cpu = cpu,
    audioStack = stack,
    notes = notes,
    lastError = lastError,
)

internal fun dspMetrics(
    cpuLoadPercent: Float = 0f,
    endToEndLatencyMs: Float = 0f,
    xruns: Int = 0,
) = DspMetrics(
    inputRms = 0f,
    inputPeak = 0f,
    outputRms = 0f,
    outputPeak = 0f,
    cpuLoadPercent = cpuLoadPercent,
    endToEndLatencyMs = endToEndLatencyMs,
    xruns = xruns,
)
