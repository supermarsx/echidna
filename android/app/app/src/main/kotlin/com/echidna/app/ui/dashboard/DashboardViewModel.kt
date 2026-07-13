package com.echidna.app.ui.dashboard

import androidx.lifecycle.ViewModel
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.DspMetrics
import com.echidna.app.model.EngineStatus
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.Preset
import kotlinx.coroutines.flow.StateFlow

class DashboardViewModel : ViewModel() {
    private val repo = ControlStateRepository

    val masterEnabled: StateFlow<Boolean> = repo.masterEnabled
    val bypass: StateFlow<Boolean> = repo.bypass
    val activePreset: StateFlow<Preset> = repo.activePreset
    val latencyMode: StateFlow<LatencyMode> = repo.latencyMode
    val sidetoneEnabled: StateFlow<Boolean> = repo.sidetoneEnabled
    val sidetoneLevel: StateFlow<Float> = repo.sidetoneLevel
    val engineStatus: StateFlow<EngineStatus> = repo.engineStatus
    val metrics: StateFlow<DspMetrics> = repo.metrics
    val presets: StateFlow<List<Preset>> = repo.presets

    fun toggleMaster() = repo.toggleMaster()

    fun setMaster(enabled: Boolean) = repo.setMasterEnabled(enabled)

    fun selectPreset(presetId: String) = repo.selectPreset(presetId)

    fun cyclePreset() = repo.cyclePreset()

    fun setLatencyMode(mode: LatencyMode) = repo.setLatencyMode(mode)

    fun setSidetoneEnabled(enabled: Boolean) = repo.setSidetoneEnabled(enabled)

    fun setSidetone(levelDb: Float) = repo.updateSidetone(levelDb)

    // Routes through the service: forces global bypass for the panic hold window (spec §12).
    fun triggerPanic() = repo.triggerPanic()
}
