package com.echidna.app.ui.compatibility

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.CompatibilityResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class CompatibilityWizardUiState(
    val running: Boolean = false,
    val result: CompatibilityResult? = null
)

class CompatibilityWizardViewModel : ViewModel() {
    private val repo = ControlStateRepository
    private val _uiState = MutableStateFlow(CompatibilityWizardUiState())
    val uiState: StateFlow<CompatibilityWizardUiState> = _uiState.asStateFlow()

    init {
        runProbes()
    }

    fun runProbes() {
        // Guard against double-tap / re-entry: ignore taps while a run is in flight.
        if (_uiState.value.running) return
        viewModelScope.launch {
            // Enter the probing state (button greys out, progress bar shows). Keep any
            // existing result visible so the card doesn't flicker while re-probing.
            _uiState.value = _uiState.value.copy(running = true)
            try {
                repo.runCompatibilityProbe()
                // Bound the wait so a stalled privileged probe can never leave the
                // button permanently greyed-out.
                val result = withTimeout(PROBE_TIMEOUT_MS) {
                    repo.compatibilityState.filterNotNull().first()
                }
                _uiState.value = CompatibilityWizardUiState(running = false, result = result)
            } catch (timeout: TimeoutCancellationException) {
                // Probe stalled — drop back to the retry state rather than hang.
                _uiState.value = CompatibilityWizardUiState(running = false, result = null)
            } finally {
                // Belt-and-suspenders: whatever happened (error, cancellation), make sure
                // the probing flag is cleared so the UI never stays stuck disabled.
                if (_uiState.value.running) {
                    _uiState.value = _uiState.value.copy(running = false)
                }
            }
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 15_000L
    }
}
