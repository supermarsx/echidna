package com.echidna.app.ui.diagnostics

import androidx.lifecycle.ViewModel
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.CompatibilityResult
import com.echidna.app.model.DspMetrics
import com.echidna.app.model.EngineStatus
import kotlinx.coroutines.flow.StateFlow

class DiagnosticsViewModel : ViewModel() {
    private val repo = ControlStateRepository

    val engineStatus: StateFlow<EngineStatus> = repo.engineStatus
    val metrics: StateFlow<DspMetrics> = repo.metrics
    val compatibility: StateFlow<CompatibilityResult?> = repo.compatibilityState

    fun refreshCompatibility() = repo.runCompatibilityProbe()
}
