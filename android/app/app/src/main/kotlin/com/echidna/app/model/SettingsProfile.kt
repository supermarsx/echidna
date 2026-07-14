package com.echidna.app.model

enum class DspEngineMode(
    val id: String,
    val label: String,
    val description: String
) {
    NATIVE_FIRST(
        id = "native_first",
        label = "Native first",
        description = "Prefer the native AAudio/OpenSL path and fall back only when required."
    ),
    LOW_LATENCY(
        id = "low_latency",
        label = "Low latency",
        description = "Favor shorter buffers for live monitoring and calls."
    ),
    COMPATIBILITY(
        id = "compatibility",
        label = "Compatibility",
        description = "Prefer the compatibility path for devices with fragile audio stacks."
    );

    companion object {
        fun fromId(id: String?): DspEngineMode =
            values().firstOrNull { it.id == id } ?: NATIVE_FIRST
    }
}

data class SettingsState(
    val startWithSystem: Boolean = false,
    val autoStartEngine: Boolean = false,
    val restoreLastProfile: Boolean = true,
    val engineMode: DspEngineMode = DspEngineMode.NATIVE_FIRST,
    val latencyMode: LatencyMode = LatencyMode.LOW_LATENCY,
    val sidetoneEnabled: Boolean = false,
    val sidetoneLevelDb: Float = -24f,
    val debugMode: Boolean = false,
    val telemetryOptIn: Boolean = false,
    val verboseLogging: Boolean = false,
    val failClosed: Boolean = true,
    val autoBypassOnError: Boolean = true,
    val panicHoldMinutes: Int = 5,
    val persistentNotification: Boolean = true,
    val quickControlsEnabled: Boolean = true,
    val widgetControlsEnabled: Boolean = true,
    val showInstallAlerts: Boolean = true,
    val showBridgeAlerts: Boolean = true,
    val showHardwareAlerts: Boolean = true,
    val showInstallMixupAlerts: Boolean = true,
    val alertLatencyThresholdMs: Int = 40,
    val alertXrunThreshold: Int = 3,
    val remindCompatibilityProbe: Boolean = true,
    val masterEnabled: Boolean = true,
    val bypass: Boolean = false,
    val defaultPresetId: String? = null
)

data class SettingsProfile(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val settings: SettingsState
)
