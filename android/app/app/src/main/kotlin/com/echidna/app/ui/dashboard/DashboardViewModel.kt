package com.echidna.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.DspMetrics
import com.echidna.app.model.EngineStatus
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.Preset
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel : ViewModel() {
    private val repo = ControlStateRepository

    val masterEnabled: StateFlow<Boolean> = repo.masterEnabled
    val activePreset: StateFlow<Preset> = repo.activePreset
    val latencyMode: StateFlow<LatencyMode> = repo.latencyMode
    val sidetoneLevel: StateFlow<Float> = repo.sidetoneLevel
    val engineStatus: StateFlow<EngineStatus> = repo.engineStatus
    val metrics: StateFlow<DspMetrics> = repo.metrics
    val presets: StateFlow<List<Preset>> = repo.presets

    fun toggleMaster() = repo.toggleMaster()

    fun setMaster(enabled: Boolean) = repo.setMasterEnabled(enabled)

    fun selectPreset(presetId: String) = repo.selectPreset(presetId)

    fun cyclePreset() = repo.cyclePreset()

    fun setLatencyMode(mode: LatencyMode) = repo.setLatencyMode(mode)

    fun setSidetone(levelDb: Float) = repo.updateSidetone(levelDb)

    fun triggerPanic() {
        repo.setMasterEnabled(false)
        viewModelScope.launch {
            repo.refreshEngineStatus(active = false)
        }
    }
}
