package com.echidna.app.ui.compatibility

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echidna.app.model.AudioStackProbe
import com.echidna.app.model.CompatibilityResult

@Composable
fun CompatibilityWizardScreen(viewModel: CompatibilityWizardViewModel, onFinish: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val result = state.result

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Compatibility Wizard",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Device probes for the control service, SELinux posture, and audio path.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.running) {
            item { RunningProbeCard() }
        }

        result?.let {
            item { ResultOverviewCard(it) }
            item {
                CheckGroupCard(
                    title = "Device environment",
                    checks = listOf(selinuxCheck(it))
                )
            }
            item {
                CheckGroupCard(
                    title = "Audio path",
                    checks = it.audioStack.map(::audioStackCheck)
                )
            }
            if (it.notes.isNotEmpty()) {
                item {
                    CheckGroupCard(
                        title = "Action notes",
                        checks = it.notes.map(::noteCheck)
                    )
                }
            }
        }

        item {
            WizardActions(
                running = state.running,
                hasResult = result != null,
                onRun = viewModel::runProbes,
                onFinish = onFinish
            )
        }
    }
}

@Composable
private fun RunningProbeCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Column {
                    Text("Running probes", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Waiting for the service to report fresh SELinux and HAL state.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ResultOverviewCard(result: CompatibilityResult) {
    val checks = listOf(selinuxCheck(result)) +
        result.audioStack.map(::audioStackCheck) +
        result.notes.map(::noteCheck)
    val failCount = checks.count { it.tone == StatusTone.FAIL }
    val warnCount = checks.count { it.tone == StatusTone.WARN }
    val tone = when {
        failCount > 0 -> StatusTone.FAIL
        warnCount > 0 -> StatusTone.WARN
        else -> StatusTone.PASS
    }
    val headline = when (tone) {
        StatusTone.PASS -> "Ready for native audio probes"
        StatusTone.WARN -> "Usable with notes to review"
        StatusTone.FAIL -> "Blocked until service access works"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StatusBadge(tone = tone)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = buildString {
                        append("${checks.count { it.tone == StatusTone.PASS }} pass")
                        append(" | $warnCount warn")
                        append(" | $failCount fail")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CheckGroupCard(title: String, checks: List<CompatibilityCheck>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            checks.forEachIndexed { index, check ->
                if (index > 0) HorizontalDivider()
                CheckRow(check)
            }
        }
    }
}

@Composable
private fun CheckRow(check: CompatibilityCheck) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusBadge(tone = check.tone, compact = true)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = check.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                StatusPill(tone = check.tone)
            }
            check.value?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            check.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WizardActions(
    running: Boolean,
    hasResult: Boolean,
    onRun: () -> Unit,
    onFinish: () -> Unit
) {
    if (hasResult) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onRun,
                enabled = !running,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Run again")
            }
            Button(
                onClick = onFinish,
                enabled = !running,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Finish")
            }
        }
    } else {
        Button(
            onClick = onRun,
            enabled = !running,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (running) "Probing..." else "Run probes")
        }
    }
}

@Composable
private fun StatusBadge(tone: StatusTone, compact: Boolean = false) {
    val accent = statusColor(tone)
    val size = if (compact) 28.dp else 44.dp
    Box(
        modifier = Modifier
            .size(size)
            .background(accent.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = tone.icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(if (compact) 18.dp else 26.dp)
        )
    }
}

@Composable
private fun StatusPill(tone: StatusTone) {
    val accent = statusColor(tone)
    Surface(
        color = accent.copy(alpha = 0.18f),
        contentColor = accent,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = tone.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun statusColor(tone: StatusTone): Color = when (tone) {
    StatusTone.PASS -> Color(0xFF4CAF50)
    StatusTone.WARN -> Color(0xFFFFB300)
    StatusTone.FAIL -> MaterialTheme.colorScheme.error
}

private fun selinuxCheck(result: CompatibilityResult): CompatibilityCheck =
    CompatibilityCheck(
        label = "SELinux",
        value = result.selinuxStatus,
        detail = when (selinuxTone(result.selinuxStatus)) {
            StatusTone.PASS -> "Policy posture reported by the control service."
            StatusTone.WARN -> "Review the status before relying on native hooks."
            StatusTone.FAIL -> "The probe could not confirm SELinux compatibility."
        },
        tone = selinuxTone(result.selinuxStatus)
    )

private fun audioStackCheck(stack: AudioStackProbe): CompatibilityCheck {
    val tone = when {
        stack.name.contains("control service", ignoreCase = true) -> StatusTone.FAIL
        stack.supported -> StatusTone.PASS
        else -> StatusTone.WARN
    }
    return CompatibilityCheck(
        label = stack.name,
        value = if (stack.supported) {
            stack.latencyEstimateMs?.let { "Supported, estimated $it ms" } ?: "Supported"
        } else {
            "Unavailable"
        },
        detail = stack.message.takeIf { it.isNotBlank() },
        tone = tone
    )
}

private fun noteCheck(note: String): CompatibilityCheck =
    CompatibilityCheck(
        label = note,
        value = null,
        detail = noteAction(note),
        tone = noteTone(note)
    )

private fun selinuxTone(status: String): StatusTone {
    val lower = status.lowercase()
    return when {
        lower.contains("unknown") ||
            lower.contains("unavailable") ||
            lower.contains("error") ||
            lower.contains("denied") -> StatusTone.FAIL
        lower.contains("permissive") ||
            lower.contains("warning") ||
            lower.contains("partial") -> StatusTone.WARN
        else -> StatusTone.PASS
    }
}

private fun noteTone(note: String): StatusTone {
    val lower = note.lowercase()
    return when {
        lower.contains("last error") ||
            lower.contains("unavailable") ||
            lower.contains("not bound") -> StatusTone.FAIL
        lower.contains("not installed") ||
            lower.contains("disabled") ||
            lower.contains("fallback") ||
            lower.contains("absent") ||
            lower.contains("unknown") -> StatusTone.WARN
        else -> StatusTone.PASS
    }
}

private fun noteAction(note: String): String? {
    val lower = note.lowercase()
    return when {
        lower.contains("not installed") -> "Install the Magisk module before expecting native hooks."
        lower.contains("disabled") -> "Enable the reported component, then run the probes again."
        lower.contains("fallback") -> "Native hooks may be unavailable; expect the LSPosed shim path."
        lower.contains("last error") -> "Fix the reported service error and retry."
        lower.contains("unavailable") || lower.contains("not bound") ->
            "Bind the in-process control service and retry the probe."
        else -> null
    }
}

private data class CompatibilityCheck(
    val label: String,
    val value: String?,
    val detail: String?,
    val tone: StatusTone
)

private enum class StatusTone(
    val label: String,
    val icon: ImageVector
) {
    PASS("PASS", Icons.Filled.CheckCircle),
    WARN("WARN", Icons.Filled.Warning),
    FAIL("FAIL", Icons.Filled.ErrorOutline)
}
