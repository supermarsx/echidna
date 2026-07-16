package com.echidna.app.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echidna.app.ui.components.AlertSeverity
import com.echidna.app.ui.components.AudioMetersCard
import com.echidna.app.ui.components.EngineStatusCard
import com.echidna.app.ui.components.PersistentDismissibleAlert
import com.echidna.app.ui.components.rememberDismissedAlertsStore
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.Preset
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onOpenInstall: () -> Unit = {}
) {
    val masterEnabled by viewModel.masterEnabled.collectAsStateWithLifecycle()
    val bypass by viewModel.bypass.collectAsStateWithLifecycle()
    val activePreset by viewModel.activePreset.collectAsStateWithLifecycle()
    val latencyMode by viewModel.latencyMode.collectAsStateWithLifecycle()
    val sidetoneEnabled by viewModel.sidetoneEnabled.collectAsStateWithLifecycle()
    val sidetoneLevel by viewModel.sidetoneLevel.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        InstallRiskWarningCard(
            nativeInstalled = engineStatus.nativeInstalled,
            onOpenInstall = onOpenInstall
        )
        MasterControlCard(
            enabled = masterEnabled,
            onToggle = { checked ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.setMaster(checked)
            }
        )
        EngineStatusCard(
            status = engineStatus,
            masterEnabled = masterEnabled,
            bypass = bypass
        )
        AudioMetersCard(metrics = metrics, active = engineStatus.active)
        DashCard {
            PresetPicker(
                presets = presets,
                active = activePreset,
                onSelect = viewModel::selectPreset,
                onCycle = viewModel::cyclePreset
            )
        }
        DashCard {
            LatencySelector(current = latencyMode, onSelect = viewModel::setLatencyMode)
        }
        DashCard {
            SidetoneSlider(
                enabled = sidetoneEnabled,
                levelDb = sidetoneLevel,
                onEnabledChange = viewModel::setSidetoneEnabled,
                onChange = viewModel::setSidetone
            )
        }
        Button(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.triggerPanic()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Filled.Bolt, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Panic — Bypass Engine", fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * The dashboard's install-risk warning, now a dismissible alert. It is safety/recovery-relevant, so
 * a permanent "Don't remind" is honored but scoped to the current install state: a MATERIAL change
 * (native module installed <-> not installed) surfaces the recovery warning once more. The plain
 * "Dismiss" is reconciled against the same state key. The action button opens the guided installer,
 * which honestly detects root/Magisk before offering to install the bundled engine module.
 */
@Composable
private fun InstallRiskWarningCard(nativeInstalled: Boolean, onOpenInstall: () -> Unit) {
    val store = rememberDismissedAlertsStore()
    val stateKey = if (nativeInstalled) "installed" else "not_installed"
    val key = "dashboard.install_risk:$stateKey"
    LaunchedEffect(key) {
        store.reconcileActive(setOf(key), "dashboard.install_risk:")
    }
    PersistentDismissibleAlert(
        alertKey = key,
        permanentAlertKey = key,
        store = store,
        title = "Root module / install risk",
        message = "Echidna's Android capture-path interception and Magisk/Zygisk module install " +
            "path are very hard and will likely not work on many phones. Do not flash or rely on " +
            "the module unless you can recover the device.",
        severity = AlertSeverity.ERROR,
        actionLabel = "Set up / install engine",
        onAction = onOpenInstall,
    )
}

/**
 * The dashboard's hero control: a large, colour-shifting card carrying the master
 * enable/bypass switch. The container tint animates between the primary and a neutral
 * surface as the engine is enabled/disabled, giving immediate at-a-glance feedback.
 */
@Composable
private fun MasterControlCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val container by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(400),
        label = "masterContainer"
    )
    val accent = if (enabled) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IconBadge(accent = accent)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Voice processing",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
                Text(
                    text = if (enabled) "Master enabled" else "Master off — audio untouched",
                    style = MaterialTheme.typography.bodyMedium,
                    color = accent.copy(alpha = 0.8f)
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun IconBadge(accent: Color) {
    Column(
        modifier = Modifier
            .size(48.dp)
            .background(accent.copy(alpha = 0.15f), CircleShape),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.GraphicEq,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(28.dp)
        )
    }
}

/** A plain elevated container that gives the grouped controls a consistent card surface. */
@Composable
private fun DashCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
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
private fun SidetoneSlider(
    enabled: Boolean,
    levelDb: Float,
    onEnabledChange: (Boolean) -> Unit,
    onChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text = "Sidetone (${levelDb.roundToInt()} dB)", style = MaterialTheme.typography.titleMedium)
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        Slider(
            value = levelDb,
            onValueChange = onChange,
            valueRange = -60f..-6f,
            enabled = enabled
        )
    }
}
