package com.echidna.app.ui.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echidna.app.model.CompatibilityResult
import com.echidna.app.model.CpuHeatPoint
import com.echidna.app.model.FormantState
import com.echidna.app.model.HookTelemetry
import com.echidna.app.model.LatencyBucket
import com.echidna.app.model.ModuleStatus
import com.echidna.app.model.TelemetrySnapshot
import com.echidna.app.model.TunerState
import com.echidna.app.ui.components.AudioMetersCard
import com.echidna.app.ui.components.EngineStatusCard
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
    val moduleStatus by viewModel.moduleStatus.collectAsStateWithLifecycle()
    val latencyMode by viewModel.latencyMode.collectAsStateWithLifecycle()
    val masterEnabled by viewModel.masterEnabled.collectAsStateWithLifecycle()
    val bypass by viewModel.bypass.collectAsStateWithLifecycle()
    val probing by viewModel.probing.collectAsStateWithLifecycle()
    var exportSummary by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(DiagnosticsTab.OVERVIEW) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Diagnostics",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )
        ScrollableTabRow(
            selectedTabIndex = selectedTab.ordinal,
            edgePadding = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            DiagnosticsTab.values().forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.title) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            when (selectedTab) {
                DiagnosticsTab.OVERVIEW -> {
                    item {
                        EngineStatusCard(
                            status = status,
                            masterEnabled = masterEnabled,
                            bypass = bypass
                        )
                    }
                    item {
                        AudioMetersCard(metrics = metrics, active = status.active)
                    }
                    item {
                        OverviewProbeCard(
                            moduleStatus = moduleStatus,
                            compatibility = compatibility,
                            masterEnabled = masterEnabled,
                            bypass = bypass
                        )
                    }
                }

                DiagnosticsTab.PIPELINE -> {
                    item {
                        AudioPipelineView(
                            telemetry = telemetry,
                            moduleStatus = moduleStatus,
                            bypass = bypass
                        )
                    }
                    item { HooksCard(telemetry.hooks) }
                    item {
                        AdvancedDiagnosticsSection(
                            moduleStatus = moduleStatus,
                            telemetry = telemetry,
                            metrics = metrics,
                            latencyMode = latencyMode,
                            masterEnabled = masterEnabled,
                            bypass = bypass
                        )
                    }
                }

                DiagnosticsTab.LATENCY -> {
                    item {
                        LatencyCpuCard(
                            latency = latency,
                            cpuHeatmap = cpuHeatmap,
                            telemetry = telemetry
                        )
                    }
                    item {
                        RuntimeStatsCard(
                            telemetry = telemetry,
                            active = status.active
                        )
                    }
                }

                DiagnosticsTab.TUNER -> {
                    item {
                        TunerFormantCard(
                            tuner = tuner,
                            formant = formant,
                            telemetry = telemetry
                        )
                    }
                }

                DiagnosticsTab.LOGS -> {
                    item {
                        TelemetryExportCard(
                            telemetryOptIn = telemetryOptIn,
                            exportSummary = exportSummary,
                            onTelemetryOptIn = viewModel::setTelemetryOptIn,
                            onExport = {
                                viewModel.exportTelemetry { result ->
                                    exportSummary = result?.let {
                                        "Exported telemetry (${it.length} bytes)"
                                    }
                                }
                            }
                        )
                    }
                    item {
                        ProbeActionsCard(
                            probing = probing,
                            onRefreshCompatibility = viewModel::refreshCompatibility
                        )
                    }
                    compatibility?.let { result ->
                        item { CompatibilityResultCard(result) }
                    }
                    item {
                        SafetyNotesCard(moduleStatus = moduleStatus)
                    }
                }
            }
        }
    }
}

private enum class DiagnosticsTab(val title: String) {
    OVERVIEW("Overview"),
    PIPELINE("Pipeline & hooks"),
    LATENCY("Latency & CPU"),
    TUNER("Tuner & formant"),
    LOGS("Logs & safety")
}

@Composable
private fun OverviewProbeCard(
    moduleStatus: ModuleStatus?,
    compatibility: CompatibilityResult?,
    masterEnabled: Boolean,
    bypass: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Control plane", style = MaterialTheme.typography.titleMedium)
            StatusLine(
                label = "Master",
                value = if (masterEnabled) "Enabled" else "Off",
                positive = masterEnabled
            )
            StatusLine(
                label = "Bypass",
                value = if (bypass) "On" else "Off",
                positive = !bypass
            )
            StatusLine(
                label = "Module",
                value = when {
                    moduleStatus == null -> "Not bound"
                    moduleStatus.magiskModuleInstalled -> "Installed"
                    else -> "Not installed"
                },
                positive = moduleStatus?.magiskModuleInstalled == true
            )
            StatusLine(
                label = "Zygisk",
                value = when {
                    moduleStatus == null -> "Unknown"
                    moduleStatus.zygiskEnabled -> "Enabled"
                    else -> "Disabled"
                },
                positive = moduleStatus?.zygiskEnabled == true
            )
            compatibility?.let {
                HorizontalDivider()
                Text(
                    text = "Latest probe: SELinux ${it.selinuxStatus}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HooksCard(hooks: List<HookTelemetry>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Hooks", style = MaterialTheme.typography.titleMedium)
            if (hooks.isEmpty()) {
                Text(
                    "No hook attempts recorded yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                hooks.forEach { hook -> HookStatusRow(hook) }
            }
        }
    }
}

@Composable
private fun LatencyCpuCard(
    latency: List<LatencyBucket>,
    cpuHeatmap: List<CpuHeatPoint>,
    telemetry: TelemetrySnapshot
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Latency histogram", style = MaterialTheme.typography.titleMedium)
            LatencyHistogramView(latency)
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricText("Average", "%.2f ms".format(Locale.US, telemetry.averageLatencyMs))
                MetricText("XRuns", telemetry.xruns.toString())
            }
            HorizontalDivider()
            Text("CPU heatmap", style = MaterialTheme.typography.titleMedium)
            CpuHeatmapView(cpuHeatmap)
        }
    }
}

@Composable
private fun RuntimeStatsCard(telemetry: TelemetrySnapshot, active: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Callback timing", style = MaterialTheme.typography.titleMedium)
            StatusLine(
                label = "Engine",
                value = if (active) "Processing" else "Idle",
                positive = active
            )
            StatusLine(
                label = "Callbacks",
                value = telemetry.totalCallbacks.toString(),
                positive = telemetry.totalCallbacks > 0L
            )
            StatusLine(
                label = "Average CPU",
                value = "%.1f%%".format(Locale.US, telemetry.averageCpuPercent),
                positive = telemetry.averageCpuPercent < 70f
            )
            telemetry.samples.lastOrNull()?.let { sample ->
                Text(
                    text = "Last block: ${sample.durationUs} us wall / ${sample.cpuUs} us CPU",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TunerFormantCard(
    tuner: TunerState,
    formant: FormantState,
    telemetry: TelemetrySnapshot
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Tuner", style = MaterialTheme.typography.titleMedium)
            TunerView(tuner)
            HorizontalDivider()
            Text("Formant / pitch", style = MaterialTheme.typography.titleMedium)
            FormantVisualizer(formant, telemetry)
        }
    }
}

@Composable
private fun TelemetryExportCard(
    telemetryOptIn: Boolean,
    exportSummary: String?,
    onTelemetryOptIn: (Boolean) -> Unit,
    onExport: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Telemetry export", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Exports latency and CPU statistics without preset names or IDs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = telemetryOptIn,
                    onCheckedChange = onTelemetryOptIn
                )
            }
            Button(
                onClick = onExport,
                enabled = telemetryOptIn,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Export anonymized telemetry")
            }
            exportSummary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProbeActionsCard(
    probing: Boolean,
    onRefreshCompatibility: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Compatibility probes", style = MaterialTheme.typography.titleMedium)
            if (probing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Button(
                onClick = onRefreshCompatibility,
                enabled = !probing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (probing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (probing) "Probing..." else "Run compatibility probes")
            }
        }
    }
}

@Composable
private fun CompatibilityResultCard(result: CompatibilityResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Last compatibility result", style = MaterialTheme.typography.titleMedium)
            StatusLine("SELinux", result.selinuxStatus, positive = !result.selinuxStatus.contains("unknown", true))
            result.audioStack.forEach { stack ->
                StatusLine(
                    label = stack.name,
                    value = buildString {
                        append(if (stack.supported) "Supported" else "Unavailable")
                        stack.latencyEstimateMs?.let { append(" ($it ms)") }
                    },
                    positive = stack.supported
                )
                if (stack.message.isNotBlank()) {
                    Text(
                        text = stack.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (result.notes.isNotEmpty()) {
                HorizontalDivider()
                result.notes.forEach { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SafetyNotesCard(moduleStatus: ModuleStatus?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Safety notes", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "This screen reads status and probes only; it does not change root, SELinux, or module policy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Per-app processing still follows the whitelist and preset bindings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            moduleStatus?.lastError?.let {
                Text(
                    text = "Last service error: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, positive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        StatusPill(text = value, positive = positive)
    }
}

@Composable
private fun MetricText(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun LatencyHistogramView(buckets: List<LatencyBucket>) {
    if (buckets.isEmpty()) {
        Text("No latency data yet", style = MaterialTheme.typography.bodySmall)
        return
    }
    val maxCount = buckets.maxOf { it.count }.coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        buckets.forEach { bucket ->
            Column {
                Text(
                    text = "${bucket.label} (${bucket.count})",
                    style = MaterialTheme.typography.bodySmall
                )
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .height(12.dp)
                ) {
                    val fraction = bucket.count.toFloat() / maxCount.toFloat()
                    drawRoundRect(
                        color = barColor,
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
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
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
        "Shift ${String.format(Locale.US, "%.0f", formant.shiftCents)} cents | " +
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
        }.joinToString(" | ")
        Text(details, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusPill(text: String, positive: Boolean) {
    val accent = if (positive) {
        Color(0xFF4CAF50)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = accent.copy(alpha = 0.18f),
        contentColor = accent,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
