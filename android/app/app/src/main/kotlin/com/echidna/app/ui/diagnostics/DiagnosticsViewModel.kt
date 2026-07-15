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
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.ModuleStatus
import com.echidna.app.model.TelemetrySnapshot
import com.echidna.app.model.TunerState
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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

    // Read-only flows backing the Advanced diagnostics section: the real de-faked
    // module/SELinux/HAL probe, the configured latency target, and global control state.
    val moduleStatus: StateFlow<ModuleStatus?> = repo.moduleStatus
    val latencyMode: StateFlow<LatencyMode> = repo.latencyMode
    val masterEnabled: StateFlow<Boolean> = repo.masterEnabled
    val bypass: StateFlow<Boolean> = repo.bypass

    // Drives the "Run Compatibility Probes" button's progress bar + greyed-out state.
    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    /**
     * Kicks off the SELinux/HAL/module probe and holds a [probing] flag true for its
     * duration so the button can show a progress indicator and disable itself. Mirrors the
     * Compatibility Wizard's guard/timeout pattern: a re-entry guard blocks double-taps, the
     * wait is bounded so a stalled privileged probe can never leave the button stuck greyed,
     * and a finally block always clears the flag.
     */
    fun refreshCompatibility() {
        if (_probing.value) return
        viewModelScope.launch {
            _probing.value = true
            try {
                repo.runCompatibilityProbe()
                withTimeout(PROBE_TIMEOUT_MS) {
                    repo.compatibilityState.filterNotNull().first()
                }
            } catch (timeout: TimeoutCancellationException) {
                // Probe stalled — drop the flag so the button re-enables rather than hang.
            } finally {
                _probing.value = false
            }
        }
    }

    fun exportTelemetry(includeTrends: Boolean = true, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            onResult(repo.exportTelemetry(includeTrends))
        }
    }

    fun exportDiagnostics(includeTrends: Boolean = true, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            onResult(repo.exportDiagnostics(includeTrends))
        }
    }

    fun setTelemetryOptIn(enabled: Boolean) {
        repo.setTelemetryOptIn(enabled)
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 15_000L
    }
}
