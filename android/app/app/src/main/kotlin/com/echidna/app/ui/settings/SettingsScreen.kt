package com.echidna.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onLaunchCompatibility: () -> Unit) {
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val masterEnabled by viewModel.masterEnabled.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val defaultId by viewModel.defaultPresetId.collectAsStateWithLifecycle()
    val persistentNotification by viewModel.persistentNotification.collectAsStateWithLifecycle()

    val defaultPresetName = presets.firstOrNull { it.id == defaultId }?.name ?: "Unknown"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineSmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Engine Status: ${engineStatus.summary}")
                Text(text = "Master Enabled: $masterEnabled")
                Text(text = "Default Preset: $defaultPresetName")
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Persistent Notification")
                Text(text = "Keep quick toggles available in the status bar.", style = MaterialTheme.typography.bodySmall)
                Switch(checked = persistentNotification, onCheckedChange = viewModel::setPersistentNotification)
            }
        }
        Button(onClick = onLaunchCompatibility, modifier = Modifier.fillMaxWidth()) {
            Text("Run Compatibility Wizard")
        }
    }
}
