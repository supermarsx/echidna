package com.echidna.app.model

/**
 * Combined module / SELinux / HAL status returned by the control service's
 * getModuleStatus()/refreshStatus() (t2-e6 signatures §3). Replaces the app's
 * previously fabricated engine-status and compatibility-wizard fallback data.
 */
data class ModuleStatus(
    val magiskModuleInstalled: Boolean,
    val zygiskEnabled: Boolean,
    val selinuxState: String,
    val selinuxStatus: String,
    val policyToolAvailable: Boolean,
    val policyAppliedVerified: Boolean,
    val nativeRouteVerified: Boolean,
    val javaFallbackRecommended: Boolean,
    val cpu: CpuArchInfo,
    val audioStack: AudioStackInfo,
    val notes: String?,
    val lastError: String?
)

data class CpuArchInfo(
    val primaryAbi: String,
    val supportedAbis: List<String>,
    val cpuFamily: String,
    val is64Bit: Boolean,
    val zygiskAbi: String,
    val moduleSupported: Boolean,
    val nativeHooksSupported: Boolean,
    val supportLevel: String,
    val message: String
)

data class AudioStackInfo(
    val hal: String,
    val manufacturer: String,
    val boardPlatform: String,
    val vendorFamily: String,
    val aaudioSupported: Boolean,
    val openSlEsAvailable: Boolean,
    val audioFlingerClientAvailable: Boolean,
    val tinyAlsaAvailable: Boolean,
    val lowLatency: Boolean,
    val proAudio: Boolean,
    val sampleRate: Int,
    val framesPerBuffer: Int
)
