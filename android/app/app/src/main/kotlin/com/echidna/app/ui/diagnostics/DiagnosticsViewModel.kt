package com.echidna.app.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.CompatibilityResult
import com.echidna.app.model.CpuHeatPoint
import com.echidna.app.model.DspMetrics
import com.echidna.app.model.EngineStatus
import com.echidna.app.model.FormantState
import com.echidna.app.model.LatencyBucket
import com.echidna.app.model.TelemetrySnapshot
import com.echidna.app.model.TunerState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DiagnosticsViewModel : ViewModel() {
    private val repo = ControlStateRepository

    val engineStatus: StateFlow<EngineStatus> = repo.engineStatus
    val metrics: StateFlow<DspMetrics> = repo.metrics
    val compatibility: StateFlow<CompatibilityResult?> = repo.compatibilityState
    val telemetry: StateFlow<TelemetrySnapshot> = repo.telemetry
    val latencyHistogram: StateFlow<List<LatencyBucket>> = repo.latencyHistogram
    val cpuHeatmap: StateFlow<List<CpuHeatPoint>> = repo.cpuHeatmap
    val tunerState: StateFlow<TunerState> = repo.tunerState
    val formantState: StateFlow<FormantState> = repo.formantState
    val telemetryOptIn: StateFlow<Boolean> = repo.telemetryOptIn

    fun refreshCompatibility() = repo.runCompatibilityProbe()

    fun exportTelemetry(includeTrends: Boolean = true, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            onResult(repo.exportTelemetry(includeTrends))
        }
    }

    fun setTelemetryOptIn(enabled: Boolean) {
        repo.setTelemetryOptIn(enabled)
    }
}
