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
    val javaFallbackActive: Boolean,
    val audioStack: AudioStackInfo,
    val notes: String?,
    val lastError: String?
)

data class AudioStackInfo(
    val hal: String,
    val aaudioSupported: Boolean,
    val lowLatency: Boolean,
    val proAudio: Boolean,
    val sampleRate: Int,
    val framesPerBuffer: Int
)
