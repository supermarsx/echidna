package com.echidna.app.ui.compatibility

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.CompatibilityResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
        viewModelScope.launch {
            _uiState.value = CompatibilityWizardUiState(running = true, result = null)
            repo.runCompatibilityProbe()
            val result = repo.compatibilityState.filterNotNull().first()
            _uiState.value = CompatibilityWizardUiState(running = false, result = result)
        }
    }
}
