package com.echidna.app.ui.preset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echidna.app.model.Preset

@Composable
fun PresetManagerScreen(viewModel: PresetManagerViewModel) {
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val active by viewModel.activePreset.collectAsStateWithLifecycle()
    val defaultId by viewModel.defaultPresetId.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Preset Manager", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { showCreateDialog = true }) { Text("New Preset") }
            OutlinedButton(onClick = { /* TODO: import presets */ }) { Text("Import") }
            OutlinedButton(onClick = { /* TODO: export presets */ }) { Text("Export") }
            OutlinedButton(onClick = { /* TODO: share preset */ }) { Text("Share") }
        }
        Divider()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(presets) { preset ->
                PresetCard(
                    preset = preset,
                    isActive = preset.id == active.id,
                    isDefault = preset.id == defaultId,
                    onSelect = { viewModel.selectPreset(preset.id) },
                    onDuplicate = { viewModel.duplicatePreset(preset.id) },
                    onDelete = { viewModel.deletePreset(preset.id) },
                    onRename = { showRenameDialog = preset.id },
                    onSetDefault = { viewModel.setDefaultPreset(preset.id) }
                )
            }
        }
    }

    if (showCreateDialog) {
        PresetCreateDialog(
            presets = presets,
            onCreate = { name, description, baseId ->
                viewModel.createPreset(name, description, baseId)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    val renameId = showRenameDialog
    if (renameId != null) {
        PresetRenameDialog(
            currentName = presets.firstOrNull { it.id == renameId }?.name.orEmpty(),
            onRename = { newName ->
                viewModel.renamePreset(renameId, newName)
                showRenameDialog = null
            },
            onDismiss = { showRenameDialog = null }
        )
    }
}

@Composable
private fun PresetCard(
    preset: Preset,
    isActive: Boolean,
    isDefault: Boolean,
    onSelect: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onSetDefault: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = preset.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            preset.description?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
            Text(text = "Tags: ${preset.tags.joinToString()}" )
            Text(text = "Latency: ${preset.latencyMode.label}")
            Text(text = "Dry/Wet: ${preset.dryWet}%")
            Text(text = "Modules: ${preset.modules.joinToString { it.id }}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSelect) { Text(if (isActive) "Active" else "Activate") }
                OutlinedButton(onClick = onRename) { Text("Rename") }
                OutlinedButton(onClick = onDuplicate) { Text("Duplicate") }
                OutlinedButton(onClick = onDelete, enabled = !isActive) { Text("Delete") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSetDefault, enabled = !isDefault) { Text(if (isDefault) "Default" else "Set Default") }
            }
        }
    }
}

@Composable
private fun PresetCreateDialog(
    presets: List<Preset>,
    onCreate: (String, String?, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var baseId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onCreate(name, description.ifBlank { null }, baseId) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Create Preset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") })
                Text(text = "Base on:")
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    item {
                        TextButton(onClick = { baseId = null }) { Text(if (baseId == null) "• Empty" else "Empty") }
                    }
                    items(presets) { preset ->
                        TextButton(onClick = { baseId = preset.id }) {
                            val prefix = if (baseId == preset.id) "• " else ""
                            Text("$prefix${preset.name}")
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun PresetRenameDialog(
    currentName: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Rename Preset") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }) }
    )
}
