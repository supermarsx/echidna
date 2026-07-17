package com.echidna.app.ui.settings

import androidx.lifecycle.ViewModel
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.AccentColor
import com.echidna.app.model.DspEngineMode
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.LegacyPreprocessorControlState
import com.echidna.app.model.ThemeMode
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(
    val legacyPreprocessorState: StateFlow<LegacyPreprocessorControlState> =
        ControlStateRepository.legacyPreprocessorState,
    private val legacyPreprocessorSetter: (Boolean) -> Unit =
        ControlStateRepository::setLegacyPreprocessorEnabled,
) : ViewModel() {
    private val repo = ControlStateRepository

    val engineStatus = repo.engineStatus
    val presets = repo.presets
    val defaultPresetId = repo.defaultPresetId
    val settingsState = repo.settingsState
    val settingsProfiles = repo.settingsProfiles
    val activeSettingsProfileId = repo.activeSettingsProfileId
    val moduleStatus = repo.moduleStatus
    val compatibility = repo.compatibilityState
    val telemetry = repo.telemetry
    val whitelistBindings = repo.whitelistBindings
    val persistentNotification: StateFlow<Boolean> = repo.notificationEnabled

    fun setStartWithSystem(enabled: Boolean) = repo.setStartWithSystem(enabled)

    fun setAutoStartEngine(enabled: Boolean) = repo.setAutoStartEngine(enabled)

    fun setRestoreLastProfile(enabled: Boolean) = repo.setRestoreLastProfile(enabled)

    fun setDspEngineMode(mode: DspEngineMode) = repo.setDspEngineMode(mode)

    fun setLatencyMode(mode: LatencyMode) = repo.setLatencyMode(mode)

    fun setSidetoneEnabled(enabled: Boolean) = repo.setSidetoneEnabled(enabled)

    fun setSidetoneLevel(levelDb: Float) = repo.updateSidetone(levelDb)

    fun setLegacyPreprocessorEnabled(enabled: Boolean) = legacyPreprocessorSetter(enabled)

    fun setDebugMode(enabled: Boolean) = repo.setDebugMode(enabled)

    fun setTelemetryOptIn(enabled: Boolean) = repo.setTelemetryOptIn(enabled)

    fun setVerboseLogging(enabled: Boolean) = repo.setVerboseLogging(enabled)

    fun setFailClosed(enabled: Boolean) = repo.setFailClosed(enabled)

    fun setAutoBypassOnError(enabled: Boolean) = repo.setAutoBypassOnError(enabled)

    fun setPanicHoldMinutes(minutes: Int) = repo.setPanicHoldMinutes(minutes)

    fun setMasterEnabled(enabled: Boolean) = repo.setMasterEnabled(enabled)

    fun setBypass(enabled: Boolean) = repo.setBypass(enabled)

    fun triggerPanic() = repo.triggerPanic(repo.panicHoldMinutes.value * 60L * 1000L)

    fun setPersistentNotification(enabled: Boolean) {
        repo.setNotificationEnabled(enabled)
    }

    fun setQuickControlsEnabled(enabled: Boolean) = repo.setQuickControlsEnabled(enabled)

    fun setWidgetControlsEnabled(enabled: Boolean) = repo.setWidgetControlsEnabled(enabled)

    fun setShowInstallAlerts(enabled: Boolean) = repo.setShowInstallAlerts(enabled)

    fun setShowBridgeAlerts(enabled: Boolean) = repo.setShowBridgeAlerts(enabled)

    fun setShowHardwareAlerts(enabled: Boolean) = repo.setShowHardwareAlerts(enabled)

    fun setShowInstallMixupAlerts(enabled: Boolean) = repo.setShowInstallMixupAlerts(enabled)

    fun setAlertLatencyThresholdMs(thresholdMs: Int) =
        repo.setAlertLatencyThresholdMs(thresholdMs)

    fun setAlertXrunThreshold(threshold: Int) = repo.setAlertXrunThreshold(threshold)

    fun setRemindCompatibilityProbe(enabled: Boolean) =
        repo.setRemindCompatibilityProbe(enabled)

    fun setThemeMode(mode: ThemeMode) = repo.setThemeMode(mode)

    fun setDynamicColor(enabled: Boolean) = repo.setDynamicColor(enabled)

    fun setAccentColor(accent: AccentColor) = repo.setAccentColor(accent)

    fun setStatusPollIntervalSeconds(seconds: Int) =
        repo.setStatusPollIntervalSeconds(seconds)

    fun setHighPriorityNotification(enabled: Boolean) =
        repo.setHighPriorityNotification(enabled)

    fun setKeepScreenOn(enabled: Boolean) = repo.setKeepScreenOn(enabled)

    fun createSettingsProfile(name: String): String? = repo.createSettingsProfile(name)

    fun applySettingsProfile(profileId: String): Boolean = repo.applySettingsProfile(profileId)

    fun deleteSettingsProfile(profileId: String): Boolean = repo.deleteSettingsProfile(profileId)

    fun exportSettingsProfile(profileId: String): String? = repo.exportSettingsProfile(profileId)

    fun exportCurrentSettings(): String = repo.exportCurrentSettings()

    fun importSettingsProfile(json: String): String? = repo.importSettingsProfile(json)
}
