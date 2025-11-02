package com.echidna.app.ui.preset

import androidx.lifecycle.ViewModel
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.Preset
import kotlinx.coroutines.flow.StateFlow

class PresetManagerViewModel : ViewModel() {
    private val repo = ControlStateRepository

    val presets: StateFlow<List<Preset>> = repo.presets
    val activePreset: StateFlow<Preset> = repo.activePreset
    val defaultPresetId: StateFlow<String> = repo.defaultPresetId

    fun selectPreset(id: String) = repo.selectPreset(id)

    fun createPreset(name: String, description: String?, basePresetId: String?): String =
        repo.createPreset(name, description, basePresetId)

    fun duplicatePreset(id: String) {
        val preset = presets.value.firstOrNull { it.id == id } ?: return
        repo.createPreset("${preset.name} (copy)", preset.description, id)
    }

    fun deletePreset(id: String) = repo.deletePreset(id)

    fun renamePreset(id: String, name: String) = repo.renamePreset(id, name)

    fun setDefaultPreset(id: String) = repo.setDefaultPreset(id)
}
