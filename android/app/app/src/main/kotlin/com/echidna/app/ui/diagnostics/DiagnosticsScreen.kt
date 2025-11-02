package com.echidna.app.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel) {
    val status by viewModel.engineStatus.collectAsStateWithLifecycle()
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val compatibility by viewModel.compatibility.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(text = "Diagnostics", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "Engine: ${status.summary}")
                    Text(text = "SELinux: ${status.selinuxMode}")
                    Text(text = "Latency: ${status.latencyMs ?: 0} ms")
                    Text(text = "XRuns: ${status.xruns}")
                    status.lastError?.let { Text(text = "Error: $it", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "Metrics")
                    Text(text = "Input RMS ${metrics.inputRms} / Peak ${metrics.inputPeak}")
                    Text(text = "Output RMS ${metrics.outputRms} / Peak ${metrics.outputPeak}")
                    Text(text = "CPU ${metrics.cpuLoadPercent}%")
                    Text(text = "Latency ${metrics.endToEndLatencyMs} ms")
                }
            }
        }
        item {
            Button(onClick = viewModel::refreshCompatibility, modifier = Modifier.fillMaxWidth()) {
                Text("Run Compatibility Probes")
            }
        }
        compatibility?.let { result ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "SELinux: ${result.selinuxStatus}")
                        Text(text = "Audio Stacks")
                        result.audioStack.forEach { stack ->
                            val support = if (stack.supported) "Supported" else "Unsupported"
                            val latency = stack.latencyEstimateMs?.let { "$it ms" } ?: "n/a"
                            Text(text = "• ${stack.name}: $support ($latency)")
                            Text(text = "  ${stack.message}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(text = "Notes")
                        result.notes.forEach { Text(text = "• $it") }
                    }
                }
            }
        }
    }
}
