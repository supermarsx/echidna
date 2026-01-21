package com.echidna.app.ui.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echidna.app.model.CpuHeatPoint
import com.echidna.app.model.FormantState
import com.echidna.app.model.HookTelemetry
import com.echidna.app.model.LatencyBucket
import com.echidna.app.model.TelemetrySnapshot
import com.echidna.app.model.TunerState
import java.util.Locale

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel) {
    val status by viewModel.engineStatus.collectAsStateWithLifecycle()
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val compatibility by viewModel.compatibility.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val latency by viewModel.latencyHistogram.collectAsStateWithLifecycle()
    val cpuHeatmap by viewModel.cpuHeatmap.collectAsStateWithLifecycle()
    val tuner by viewModel.tunerState.collectAsStateWithLifecycle()
    val formant by viewModel.formantState.collectAsStateWithLifecycle()
    val telemetryOptIn by viewModel.telemetryOptIn.collectAsStateWithLifecycle()
    var exportSummary by remember { mutableStateOf<String?>(null) }

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
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = "Engine: ${status.summary}")
                    Text(text = "SELinux: ${status.selinuxMode}")
                    Text(text = "Latency: ${status.latencyMs ?: 0} ms")
                    Text(text = "XRuns: ${status.xruns}")
                    status.lastError?.let {
                        Text(text = "Error: $it", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = "Metrics")
                    Text(text = "Input RMS ${metrics.inputRms} / Peak ${metrics.inputPeak}")
                    Text(text = "Output RMS ${metrics.outputRms} / Peak ${metrics.outputPeak}")
                    Text(text = "CPU ${metrics.cpuLoadPercent}%")
                    Text(text = "Latency ${metrics.endToEndLatencyMs} ms")
                }
            }
        }
        item {
             Card(modifier = Modifier.fillMaxWidth()) {
                 Column(
                     modifier = Modifier.padding(16.dp),
                     verticalArrangement = Arrangement.spacedBy(12.dp)
                 ) {
                    Text("Latency Histogram", style = MaterialTheme.typography.titleMedium)
                     LatencyHistogramView(latency)
                     Text("CPU Heatmap", style = MaterialTheme.typography.titleMedium)
                     CpuHeatmapView(cpuHeatmap)
                 }
             }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Tuner", style = MaterialTheme.typography.titleMedium)
                    TunerView(tuner)
                    Text("Formant / Pitch", style = MaterialTheme.typography.titleMedium)
                    FormantVisualizer(formant, telemetry)
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Hooks", style = MaterialTheme.typography.titleMedium)
                    telemetry.hooks.forEach { hook ->
                        HookStatusRow(hook)
                    }
                    if (telemetry.hooks.isEmpty()) {
                        Text(
                            "No hook attempts recorded yet",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Share anonymized telemetry",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Switch(
                            checked = telemetryOptIn,
                            onCheckedChange = viewModel::setTelemetryOptIn
                        )
                    }
                    Text(
                        text = "Allows the app to export latency and CPU statistics " +
                            "without storing preset names or identifiers.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        item {
            Button(onClick = viewModel::refreshCompatibility, modifier = Modifier.fillMaxWidth()) {
                Text("Run Compatibility Probes")
            }
        }
        item {
            Button(onClick = {
                viewModel.exportTelemetry { result ->
                    exportSummary = result?.let { "Exported telemetry (${it.length} bytes)" }
                }
            }, enabled = telemetryOptIn, modifier = Modifier.fillMaxWidth()) {
                Text("Export anonymized telemetry")
            }
        }
        exportSummary?.let { summary ->
            item {
                Text(summary, style = MaterialTheme.typography.bodySmall)
            }
        }
        compatibility?.let { result ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "SELinux: ${result.selinuxStatus}")
                        Text(text = "Audio Stacks")
                        result.audioStack.forEach { stack ->
                            val support = if (stack.supported) "Supported" else "Unsupported"
                            val latency = stack.latencyEstimateMs?.let { "$it ms" } ?: "n/a"
                            Text(text = "• ${stack.name}: $support ($latency)")
                            Text(
                                text = "  ${stack.message}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(text = "Notes")
                        result.notes.forEach { Text(text = "• $it") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LatencyHistogramView(buckets: List<LatencyBucket>) {
    if (buckets.isEmpty()) {
        Text("No latency data yet", style = MaterialTheme.typography.bodySmall)
        return
    }
    val maxCount = buckets.maxOf { it.count }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        buckets.forEach { bucket ->
            Column {
                Text(
                    text = "${bucket.label} (${bucket.count})",
                    style = MaterialTheme.typography.bodySmall
                )
                Canvas(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .height(12.dp)) {
                    val fraction = bucket.count.toFloat() / maxCount.toFloat()
                    drawRoundRect(
                        color = MaterialTheme.colorScheme.primary,
                        size = size.copy(width = size.width * fraction),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CpuHeatmapView(points: List<CpuHeatPoint>) {
    if (points.isEmpty()) {
        Text("No CPU samples", style = MaterialTheme.typography.bodySmall)
        return
    }
    Canvas(modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)) {
        val barWidth = size.width / points.size
        points.forEachIndexed { index, point ->
            val intensity = (point.cpuPercent / 100f).coerceIn(0f, 1f)
            drawLine(
                color = Color(0xFF4CAF50).copy(alpha = 0.3f + 0.7f * intensity),
                start = androidx.compose.ui.geometry.Offset(index * barWidth, size.height),
                end = androidx.compose.ui.geometry.Offset(
                    index * barWidth,
                    size.height * (1f - intensity)
                ),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun TunerView(state: TunerState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Detected: ${state.detectedNote} " +
                "(${String.format(Locale.US, "%.1f", state.detectedHz)} Hz)",
            fontWeight = FontWeight.Medium
        )
        Text("Target: ${String.format(Locale.US, "%.1f", state.targetHz)} Hz")
        Text("Offset: ${String.format(Locale.US, "%.1f", state.centsOff)} cents")
    }
}

@Composable
private fun FormantVisualizer(formant: FormantState, telemetry: TelemetrySnapshot) {
    Text(
        "Shift ${String.format(Locale.US, "%.0f", formant.shiftCents)} cents  " +
            "Width ${String.format(Locale.US, "%.0f", formant.width)}",
        style = MaterialTheme.typography.bodySmall
    )
    if (telemetry.warnings.isNotEmpty()) {
        telemetry.warnings.forEach { warning ->
            Text(
                text = warning,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun HookStatusRow(hook: HookTelemetry) {
    val successRate = if (hook.attempts == 0) 0f else hook.successes.toFloat() / hook.attempts
    val label = hook.name.ifBlank { "Unknown" }
    Text(
        "$label: ${hook.successes}/${hook.attempts} " +
            "(${String.format(Locale.US, "%.0f", successRate * 100)}%)",
        style = MaterialTheme.typography.bodySmall
    )
    if (hook.symbol.isNotBlank() || hook.library.isNotBlank() || hook.reason.isNotBlank()) {
        val details = buildList {
            if (hook.library.isNotBlank()) add("lib=${hook.library}")
            if (hook.symbol.isNotBlank()) add("sym=${hook.symbol}")
            if (hook.reason.isNotBlank()) add("reason=${hook.reason}")
        }.joinToString(" • ")
        Text(details, style = MaterialTheme.typography.bodySmall)
    }
}
