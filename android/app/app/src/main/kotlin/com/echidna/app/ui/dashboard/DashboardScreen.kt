package com.echidna.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.Preset
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
    val masterEnabled by viewModel.masterEnabled.collectAsStateWithLifecycle()
    val activePreset by viewModel.activePreset.collectAsStateWithLifecycle()
    val latencyMode by viewModel.latencyMode.collectAsStateWithLifecycle()
    val sidetoneLevel by viewModel.sidetoneLevel.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text = "Master", style = MaterialTheme.typography.titleLarge)
            Switch(checked = masterEnabled, onCheckedChange = viewModel::setMaster)
        }
        PresetPicker(presets = presets, active = activePreset, onSelect = viewModel::selectPreset, onCycle = viewModel::cyclePreset)
        LatencySelector(current = latencyMode, onSelect = viewModel::setLatencyMode)
        SidetoneSlider(levelDb = sidetoneLevel, onChange = viewModel::setSidetone)
        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Engine Status", style = MaterialTheme.typography.titleMedium)
                Text(text = "Native: ${engineStatus.summary}")
                Text(text = "SELinux: ${engineStatus.selinuxMode}")
                engineStatus.latencyMs?.let { Text(text = "Latency: ${it} ms") }
                engineStatus.lastError?.let { Text(text = "Last error: $it", color = MaterialTheme.colorScheme.error) }
            }
        }
        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Meters", style = MaterialTheme.typography.titleMedium)
                Text(text = "Input RMS ${metrics.inputRms} dBFS / Peak ${metrics.inputPeak} dBFS")
                Text(text = "Output RMS ${metrics.outputRms} dBFS / Peak ${metrics.outputPeak} dBFS")
                Text(text = "CPU ${metrics.cpuLoadPercent.roundToInt()}% • Latency ${metrics.endToEndLatencyMs.roundToInt()} ms")
                Text(text = "XRuns ${metrics.xruns}")
            }
        }
        ElevatedButton(onClick = viewModel::triggerPanic, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Panic: Bypass Engine", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PresetPicker(
    presets: List<Preset>,
    active: Preset,
    onSelect: (String) -> Unit,
    onCycle: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Preset", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCycle) { Text("Cycle") }
            OutlinedButton(onClick = { expanded = true }) { Text(active.name) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                presets.forEach { preset ->
                    DropdownMenuItem(text = { Text(preset.name) }, onClick = {
                        onSelect(preset.id)
                        expanded = false
                    })
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(active.tags.toList()) { tag ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text(
                        text = tag,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun LatencySelector(current: LatencyMode, onSelect: (LatencyMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Latency Mode", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LatencyMode.values().forEach { mode ->
                val selected = mode == current
                Button(onClick = { onSelect(mode) }, enabled = !selected) {
                    Text(mode.label)
                }
            }
        }
    }
}

@Composable
private fun SidetoneSlider(levelDb: Float, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Sidetone (${levelDb.roundToInt()} dB)", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = levelDb,
            onValueChange = onChange,
            valueRange = -60f..-6f
        )
    }
}
