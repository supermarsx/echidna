package com.echidna.app.ui.settings

import androidx.lifecycle.ViewModel
import com.echidna.app.data.ControlStateRepository
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel : ViewModel() {
    private val repo = ControlStateRepository

    val masterEnabled = repo.masterEnabled
    val engineStatus = repo.engineStatus
    val presets = repo.presets
    val defaultPresetId = repo.defaultPresetId
    val persistentNotification: StateFlow<Boolean> = repo.notificationEnabled

    fun setPersistentNotification(enabled: Boolean) {
        repo.setNotificationEnabled(enabled)
    }
}
