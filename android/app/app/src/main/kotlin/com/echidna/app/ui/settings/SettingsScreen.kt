package com.echidna.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echidna.app.model.CompatibilityResult
import com.echidna.app.model.DspEngineMode
import com.echidna.app.model.EngineStatus
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.ModuleStatus
import com.echidna.app.model.SettingsProfile
import com.echidna.app.model.SettingsState
import com.echidna.app.model.TelemetrySnapshot
import com.echidna.app.model.WhitelistBindings
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onLaunchCompatibility: () -> Unit,
    onLaunchWhitelist: () -> Unit
) {
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val defaultId by viewModel.defaultPresetId.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val profiles by viewModel.settingsProfiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeSettingsProfileId.collectAsStateWithLifecycle()
    val moduleStatus by viewModel.moduleStatus.collectAsStateWithLifecycle()
    val compatibility by viewModel.compatibility.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val whitelistBindings by viewModel.whitelistBindings.collectAsStateWithLifecycle()

    var newProfileName by remember { mutableStateOf("") }
    var selectedProfileId by remember { mutableStateOf<String?>(null) }
    var importJson by remember { mutableStateOf("") }
    var exportJson by remember { mutableStateOf("") }
    var profileMessage by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(SettingsTab.ALERTS) }

    LaunchedEffect(profiles, activeProfileId) {
        if (selectedProfileId == null || profiles.none { it.id == selectedProfileId }) {
            selectedProfileId = activeProfileId ?: profiles.firstOrNull()?.id
        }
    }

    val defaultPresetName = presets.firstOrNull { it.id == defaultId }?.name ?: "Unknown"
    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }
    val alerts = buildAdvisoryAlerts(
        settings = settings,
        engineStatus = engineStatus,
        moduleStatus = moduleStatus,
        compatibility = compatibility,
        telemetry = telemetry,
        whitelistBindings = whitelistBindings
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HeaderSection(
            engineSummary = engineStatus.summary,
            defaultPresetName = defaultPresetName,
            activeProfile = profiles.firstOrNull { it.id == activeProfileId }
        )

        SettingsTabs(selectedTab = selectedTab, onSelect = { selectedTab = it })

        when (selectedTab) {
            SettingsTab.ALERTS -> {
                AdvisoryAlertsSection(
                    alerts = alerts,
                    onLaunchCompatibility = onLaunchCompatibility,
                    onLaunchWhitelist = onLaunchWhitelist
                )
                AlertPreferencesSection(
                    settings = settings,
                    onShowInstallAlerts = viewModel::setShowInstallAlerts,
                    onShowBridgeAlerts = viewModel::setShowBridgeAlerts,
                    onShowHardwareAlerts = viewModel::setShowHardwareAlerts,
                    onShowInstallMixupAlerts = viewModel::setShowInstallMixupAlerts,
                    onLatencyThreshold = viewModel::setAlertLatencyThresholdMs,
                    onXrunThreshold = viewModel::setAlertXrunThreshold,
                    onCompatibilityReminder = viewModel::setRemindCompatibilityProbe
                )
            }

            SettingsTab.STARTUP -> {
                StartupSection(
                    settings = settings,
                    onStartWithSystem = viewModel::setStartWithSystem,
                    onAutoStartEngine = viewModel::setAutoStartEngine,
                    onRestoreLastProfile = viewModel::setRestoreLastProfile,
                    onLaunchCompatibility = onLaunchCompatibility,
                    onLaunchWhitelist = onLaunchWhitelist
                )
                NotificationSection(
                    settings = settings,
                    onPersistentNotification = viewModel::setPersistentNotification,
                    onQuickControls = viewModel::setQuickControlsEnabled,
                    onWidgetControls = viewModel::setWidgetControlsEnabled
                )
            }

            SettingsTab.ENGINE -> EngineSection(
                settings = settings,
                onEngineMode = viewModel::setDspEngineMode,
                onLatencyMode = viewModel::setLatencyMode,
                onSidetoneEnabled = viewModel::setSidetoneEnabled,
                onSidetoneLevel = viewModel::setSidetoneLevel
            )

            SettingsTab.SAFETY -> SafetySection(
                settings = settings,
                onFailClosed = viewModel::setFailClosed,
                onAutoBypassOnError = viewModel::setAutoBypassOnError,
                onPanicHoldMinutes = viewModel::setPanicHoldMinutes,
                onMasterEnabled = viewModel::setMasterEnabled,
                onBypass = viewModel::setBypass,
                onPanic = viewModel::triggerPanic
            )

            SettingsTab.DIAGNOSTICS -> DiagnosticsSection(
                settings = settings,
                onDebugMode = viewModel::setDebugMode,
                onTelemetryOptIn = viewModel::setTelemetryOptIn,
                onVerboseLogging = viewModel::setVerboseLogging
            )

            SettingsTab.PROFILES -> ProfileSection(
                profiles = profiles,
                activeProfileId = activeProfileId,
                selectedProfile = selectedProfile,
                selectedProfileId = selectedProfileId,
                newProfileName = newProfileName,
                importJson = importJson,
                exportJson = exportJson,
                message = profileMessage,
                onSelectProfile = { selectedProfileId = it },
                onNewProfileName = { newProfileName = it },
                onCreateProfile = {
                    val id = viewModel.createSettingsProfile(newProfileName)
                    if (id == null) {
                        profileMessage = "Profile name is required."
                    } else {
                        selectedProfileId = id
                        newProfileName = ""
                        profileMessage = "Settings profile saved."
                    }
                },
                onApplyProfile = {
                    val id = selectedProfile?.id
                    profileMessage = if (id != null && viewModel.applySettingsProfile(id)) {
                        "Settings profile applied."
                    } else {
                        "Choose a settings profile first."
                    }
                },
                onDeleteProfile = {
                    val id = selectedProfile?.id
                    profileMessage = if (id != null && viewModel.deleteSettingsProfile(id)) {
                        selectedProfileId = profiles.firstOrNull { it.id != id }?.id
                        "Settings profile deleted."
                    } else {
                        "Choose a settings profile first."
                    }
                },
                onExportProfile = {
                    val id = selectedProfile?.id
                    exportJson = id?.let(viewModel::exportSettingsProfile).orEmpty()
                    profileMessage = if (exportJson.isBlank()) {
                        "Choose a settings profile first."
                    } else {
                        "Settings profile JSON ready."
                    }
                },
                onExportCurrent = {
                    exportJson = viewModel.exportCurrentSettings()
                    profileMessage = "Current settings JSON ready."
                },
                onImportJson = { importJson = it },
                onImportProfile = {
                    val id = viewModel.importSettingsProfile(importJson)
                    if (id == null) {
                        profileMessage = "Import failed. Check the JSON and try again."
                    } else {
                        selectedProfileId = id
                        importJson = ""
                        profileMessage = "Settings profile imported."
                    }
                }
            )
        }
    }
}

private enum class SettingsTab(val title: String) {
    ALERTS("Alerts"),
    STARTUP("Startup"),
    ENGINE("Engine"),
    SAFETY("Safety"),
    DIAGNOSTICS("Diagnostics"),
    PROFILES("Profiles")
}

@Composable
private fun SettingsTabs(selectedTab: SettingsTab, onSelect: (SettingsTab) -> Unit) {
    ScrollableTabRow(
        selectedTabIndex = selectedTab.ordinal,
        edgePadding = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        SettingsTab.values().forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onSelect(tab) },
                text = { Text(tab.title) }
            )
        }
    }
}

@Composable
private fun HeaderSection(
    engineSummary: String,
    defaultPresetName: String,
    activeProfile: SettingsProfile?
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Engine $engineSummary - Default preset $defaultPresetName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        activeProfile?.let {
            StatusPill(text = "Profile: ${it.name}")
        }
    }
}

@Composable
private fun AdvisoryAlertsSection(
    alerts: List<AdvisoryAlert>,
    onLaunchCompatibility: () -> Unit,
    onLaunchWhitelist: () -> Unit
) {
    SettingsSection(title = "Advisory Alerts") {
        Text(
            text = "Alerts never block controls. They call out install, bridge, hardware, and " +
                "hook-scope conditions that can interfere with Echidna.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (alerts.isEmpty()) {
            StatusPill(text = "No active advisories")
        } else {
            alerts.forEachIndexed { index, alert ->
                if (index > 0) HorizontalDivider()
                AlertRow(alert)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onLaunchCompatibility, modifier = Modifier.weight(1f)) {
                Text("Compatibility", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onLaunchWhitelist, modifier = Modifier.weight(1f)) {
                Text("Whitelist", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AlertPreferencesSection(
    settings: SettingsState,
    onShowInstallAlerts: (Boolean) -> Unit,
    onShowBridgeAlerts: (Boolean) -> Unit,
    onShowHardwareAlerts: (Boolean) -> Unit,
    onShowInstallMixupAlerts: (Boolean) -> Unit,
    onLatencyThreshold: (Int) -> Unit,
    onXrunThreshold: (Int) -> Unit,
    onCompatibilityReminder: (Boolean) -> Unit
) {
    SettingsSection(title = "Alert Preferences") {
        ToggleRow(
            title = "Incomplete install alerts",
            description = "Warn when the Magisk module or native engine is not detected.",
            checked = settings.showInstallAlerts,
            onCheckedChange = onShowInstallAlerts
        )
        ToggleRow(
            title = "Bridge and service alerts",
            description = "Warn about missing service status, SELinux errors, or Zygisk disablement.",
            checked = settings.showBridgeAlerts,
            onCheckedChange = onShowBridgeAlerts
        )
        ToggleRow(
            title = "Hardware and HAL alerts",
            description = "Warn about missing low-latency features, unknown HAL data, XRuns, and high latency.",
            checked = settings.showHardwareAlerts,
            onCheckedChange = onShowHardwareAlerts
        )
        ToggleRow(
            title = "Install mix-up alerts",
            description = "Warn when native and Java fallback paths may both be active for scoped apps.",
            checked = settings.showInstallMixupAlerts,
            onCheckedChange = onShowInstallMixupAlerts
        )
        ToggleRow(
            title = "Compatibility probe reminder",
            description = "Warn until a compatibility result has been collected in this app session.",
            checked = settings.remindCompatibilityProbe,
            onCheckedChange = onCompatibilityReminder
        )
        Text(
            text = "Latency alert threshold ${settings.alertLatencyThresholdMs} ms",
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = settings.alertLatencyThresholdMs.toFloat(),
            onValueChange = { onLatencyThreshold(it.roundToInt()) },
            valueRange = 5f..250f
        )
        Text(
            text = "XRun alert threshold ${settings.alertXrunThreshold}",
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = settings.alertXrunThreshold.toFloat(),
            onValueChange = { onXrunThreshold(it.roundToInt()) },
            valueRange = 1f..100f,
            steps = 98
        )
    }
}

@Composable
private fun AlertRow(alert: AdvisoryAlert) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = alert.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = alert.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = alert.category,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun StartupSection(
    settings: SettingsState,
    onStartWithSystem: (Boolean) -> Unit,
    onAutoStartEngine: (Boolean) -> Unit,
    onRestoreLastProfile: (Boolean) -> Unit,
    onLaunchCompatibility: () -> Unit,
    onLaunchWhitelist: () -> Unit
) {
    SettingsSection(title = "Startup and System Integration") {
        ToggleRow(
            title = "Start with system",
            description = "Request companion startup after boot when Android allows it.",
            checked = settings.startWithSystem,
            onCheckedChange = onStartWithSystem
        )
        ToggleRow(
            title = "Start engine after launch",
            description = "Restore voice processing without opening the dashboard first.",
            checked = settings.autoStartEngine,
            onCheckedChange = onAutoStartEngine
        )
        ToggleRow(
            title = "Restore last settings profile",
            description = "Keep the last applied settings profile selected between launches.",
            checked = settings.restoreLastProfile,
            onCheckedChange = onRestoreLastProfile
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onLaunchCompatibility, modifier = Modifier.weight(1f)) {
                Text("Compatibility Wizard", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onLaunchWhitelist, modifier = Modifier.weight(1f)) {
                Text("Per-App Whitelist", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngineSection(
    settings: SettingsState,
    onEngineMode: (DspEngineMode) -> Unit,
    onLatencyMode: (LatencyMode) -> Unit,
    onSidetoneEnabled: (Boolean) -> Unit,
    onSidetoneLevel: (Float) -> Unit
) {
    SettingsSection(title = "Engine and DSP Behavior") {
        Text(text = "DSP engine mode", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            enumValues<DspEngineMode>().forEach { mode ->
                ChoiceButton(
                    label = mode.label,
                    selected = settings.engineMode == mode,
                    onClick = { onEngineMode(mode) }
                )
            }
        }
        Text(
            text = settings.engineMode.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(text = "Latency target", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            enumValues<LatencyMode>().forEach { mode ->
                ChoiceButton(
                    label = "${mode.label} (${mode.targetMs} ms)",
                    selected = settings.latencyMode == mode,
                    onClick = { onLatencyMode(mode) }
                )
            }
        }

        ToggleRow(
            title = "Sidetone monitor",
            description = "Feed processed voice back locally for level checks.",
            checked = settings.sidetoneEnabled,
            onCheckedChange = onSidetoneEnabled
        )
        Text(
            text = "Sidetone level ${settings.sidetoneLevelDb.roundToInt()} dB",
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = settings.sidetoneLevelDb,
            onValueChange = onSidetoneLevel,
            valueRange = -60f..-6f,
            enabled = settings.sidetoneEnabled
        )
    }
}

@Composable
private fun DiagnosticsSection(
    settings: SettingsState,
    onDebugMode: (Boolean) -> Unit,
    onTelemetryOptIn: (Boolean) -> Unit,
    onVerboseLogging: (Boolean) -> Unit
) {
    SettingsSection(title = "Diagnostics and Developer") {
        ToggleRow(
            title = "Debug mode",
            description = "Show developer-oriented state in app surfaces.",
            checked = settings.debugMode,
            onCheckedChange = onDebugMode
        )
        ToggleRow(
            title = "Telemetry opt-in",
            description = "Allow local diagnostics collection from the control service.",
            checked = settings.telemetryOptIn,
            onCheckedChange = onTelemetryOptIn
        )
        ToggleRow(
            title = "Verbose logs",
            description = "Keep additional app-side diagnostics for troubleshooting.",
            checked = settings.verboseLogging,
            onCheckedChange = onVerboseLogging
        )
    }
}

@Composable
private fun SafetySection(
    settings: SettingsState,
    onFailClosed: (Boolean) -> Unit,
    onAutoBypassOnError: (Boolean) -> Unit,
    onPanicHoldMinutes: (Int) -> Unit,
    onMasterEnabled: (Boolean) -> Unit,
    onBypass: (Boolean) -> Unit,
    onPanic: () -> Unit
) {
    SettingsSection(title = "Safety and Failsafe") {
        ToggleRow(
            title = "Master enabled",
            description = "Allow Echidna to process audio for permitted apps.",
            checked = settings.masterEnabled,
            onCheckedChange = onMasterEnabled
        )
        ToggleRow(
            title = "Bypass engine",
            description = "Pass audio through untouched without changing presets.",
            checked = settings.bypass,
            onCheckedChange = onBypass
        )
        ToggleRow(
            title = "Fail closed",
            description = "Disable processing when required safety checks are missing.",
            checked = settings.failClosed,
            onCheckedChange = onFailClosed
        )
        ToggleRow(
            title = "Auto-bypass on engine error",
            description = "Prefer clean passthrough over a broken effect chain.",
            checked = settings.autoBypassOnError,
            onCheckedChange = onAutoBypassOnError
        )
        Text(
            text = "Panic hold ${settings.panicHoldMinutes} min",
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = settings.panicHoldMinutes.toFloat(),
            onValueChange = { onPanicHoldMinutes(it.roundToInt()) },
            valueRange = 1f..60f,
            steps = 58
        )
        Button(
            onClick = onPanic,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Panic Bypass")
        }
    }
}

@Composable
private fun NotificationSection(
    settings: SettingsState,
    onPersistentNotification: (Boolean) -> Unit,
    onQuickControls: (Boolean) -> Unit,
    onWidgetControls: (Boolean) -> Unit
) {
    SettingsSection(title = "Notification and Control") {
        ToggleRow(
            title = "Persistent notification",
            description = "Keep quick toggles available in the status bar.",
            checked = settings.persistentNotification,
            onCheckedChange = onPersistentNotification
        )
        ToggleRow(
            title = "Quick Settings tile",
            description = "Expose compact controls in Android quick settings.",
            checked = settings.quickControlsEnabled,
            onCheckedChange = onQuickControls
        )
        ToggleRow(
            title = "Home screen widget",
            description = "Allow the widget to mirror current engine controls.",
            checked = settings.widgetControlsEnabled,
            onCheckedChange = onWidgetControls
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileSection(
    profiles: List<SettingsProfile>,
    activeProfileId: String?,
    selectedProfile: SettingsProfile?,
    selectedProfileId: String?,
    newProfileName: String,
    importJson: String,
    exportJson: String,
    message: String?,
    onSelectProfile: (String) -> Unit,
    onNewProfileName: (String) -> Unit,
    onCreateProfile: () -> Unit,
    onApplyProfile: () -> Unit,
    onDeleteProfile: () -> Unit,
    onExportProfile: () -> Unit,
    onExportCurrent: () -> Unit,
    onImportJson: (String) -> Unit,
    onImportProfile: () -> Unit
) {
    SettingsSection(title = "Profile Management") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newProfileName,
                onValueChange = onNewProfileName,
                label = { Text("Profile name") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onCreateProfile) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save")
            }
        }

        if (profiles.isEmpty()) {
            Text(
                text = "No settings profiles saved.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            ProfilePicker(
                profiles = profiles,
                selectedProfileId = selectedProfileId,
                activeProfileId = activeProfileId,
                onSelectProfile = onSelectProfile
            )
            selectedProfile?.let {
                Text(
                    text = profileSummary(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApplyProfile, enabled = selectedProfile != null) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Apply")
                }
                OutlinedButton(onClick = onExportProfile, enabled = selectedProfile != null) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Export")
                }
                TextButton(onClick = onDeleteProfile, enabled = selectedProfile != null) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Delete")
                }
            }
        }

        OutlinedButton(onClick = onExportCurrent, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Export Current Settings")
        }

        if (exportJson.isNotBlank()) {
            SelectionContainer {
                OutlinedTextField(
                    value = exportJson,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Export JSON") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        OutlinedTextField(
            value = importJson,
            onValueChange = onImportJson,
            label = { Text("Import JSON") },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth()
        )
        FilledTonalButton(
            onClick = onImportProfile,
            enabled = importJson.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Import Settings Profile")
        }

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProfilePicker(
    profiles: List<SettingsProfile>,
    selectedProfileId: String?,
    activeProfileId: String?,
    onSelectProfile: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.first()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = selected.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { profile ->
                val suffix = if (profile.id == activeProfileId) " (active)" else ""
                DropdownMenuItem(
                    text = { Text("${profile.name}$suffix") },
                    onClick = {
                        onSelectProfile(profile.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChoiceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, enabled = false) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick) {
            Text(label)
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

private fun profileSummary(profile: SettingsProfile): String {
    val settings = profile.settings
    val engine = settings.engineMode.label
    val latency = settings.latencyMode.label
    val notification = if (settings.persistentNotification) "notification on" else "notification off"
    return "$engine - $latency - $notification"
}

private data class AdvisoryAlert(
    val title: String,
    val detail: String,
    val category: String
)

private fun buildAdvisoryAlerts(
    settings: SettingsState,
    engineStatus: EngineStatus,
    moduleStatus: ModuleStatus?,
    compatibility: CompatibilityResult?,
    telemetry: TelemetrySnapshot,
    whitelistBindings: WhitelistBindings
): List<AdvisoryAlert> = buildList {
    if (settings.showInstallAlerts) {
        if (moduleStatus == null) {
            add(
                AdvisoryAlert(
                    title = "Control service status unavailable",
                    detail = "The companion has not received module status yet. The UI remains usable, " +
                        "but native install checks may be stale or incomplete.",
                    category = "Incomplete install"
                )
            )
        } else if (!moduleStatus.magiskModuleInstalled) {
            add(
                AdvisoryAlert(
                    title = "Magisk module not detected",
                    detail = "Install or re-flash echidna-magisk.zip, reboot, then rerun the " +
                        "compatibility probe before relying on native hooks.",
                    category = "Incomplete install"
                )
            )
        }
        if (!engineStatus.nativeInstalled && settings.masterEnabled && !settings.bypass) {
            add(
                AdvisoryAlert(
                    title = "Native engine is not installed",
                    detail = "Master processing is enabled, but the native engine is not reported as " +
                        "installed. Audio should pass through until the module is present.",
                    category = "Incomplete install"
                )
            )
        }
    }

    if (settings.showBridgeAlerts) {
        if (settings.masterEnabled && !settings.bypass && whitelistBindings.enabledCount() == 0) {
            add(
                AdvisoryAlert(
                    title = "No target apps are whitelisted",
                    detail = "Echidna fails closed until at least one app is enabled in the " +
                        "Per-App Whitelist. Open the whitelist and enable each app you expect " +
                        "to intercept.",
                    category = "Hook scope"
                )
            )
        }
        engineStatus.lastError?.let { error ->
            add(
                AdvisoryAlert(
                    title = "Engine status reports an error",
                    detail = error.compactForAlert(),
                    category = "Incomplete bridge"
                )
            )
        }
        moduleStatus?.let { status ->
            if (!status.zygiskEnabled) {
                add(
                    AdvisoryAlert(
                        title = "Zygisk is disabled or not visible",
                        detail = "The native hook path depends on Zygisk. Enable it in Magisk and reboot " +
                            "if you expect native injection.",
                        category = "Incomplete bridge"
                    )
                )
            }
            if (status.selinuxState.containsAdvisoryWord() ||
                status.selinuxStatus.containsAdvisoryWord()
            ) {
                add(
                    AdvisoryAlert(
                        title = "SELinux or policy probe needs attention",
                        detail = "Reported SELinux state ${status.selinuxState}; status " +
                            "${status.selinuxStatus}. Native readers may fail closed if policy " +
                            "blocks the profile or telemetry bridge.",
                        category = "Bridge risk"
                    )
                )
            }
            status.notes?.takeIf { it.containsAdvisoryWord() }?.let { note ->
                add(
                    AdvisoryAlert(
                        title = "Module status includes a warning note",
                        detail = note.compactForAlert(),
                        category = "Bridge note"
                    )
                )
            }
            status.lastError?.let { error ->
                if (error != engineStatus.lastError) {
                    add(
                        AdvisoryAlert(
                            title = "Control bridge reported an error",
                            detail = error.compactForAlert(),
                            category = "Incomplete bridge"
                        )
                    )
                }
            }
        }
        if (settings.remindCompatibilityProbe && compatibility == null) {
            add(
                AdvisoryAlert(
                    title = "Compatibility probe has not run",
                    detail = "Run the wizard after installing or updating modules so hardware, SELinux, " +
                        "and bridge status are based on a fresh probe.",
                    category = "Bridge status"
                )
            )
        }
        compatibility?.notes
            ?.filter { it.containsAdvisoryWord() }
            ?.take(3)
            ?.forEach { note ->
                add(
                    AdvisoryAlert(
                        title = "Compatibility probe note needs review",
                        detail = note.compactForAlert(),
                        category = "Bridge status"
                    )
                )
            }
    }

    if (settings.showHardwareAlerts) {
        moduleStatus?.cpu?.let { cpu ->
            if (!cpu.moduleSupported) {
                add(
                    AdvisoryAlert(
                        title = "CPU ABI is not packaged by Echidna",
                        detail = cpu.message.ifBlank {
                            "Primary ABI ${cpu.primaryAbi.ifBlank { "unknown" }} is not supported."
                        },
                        category = "Hardware compatibility"
                    )
                )
            } else if (!cpu.nativeHooksSupported) {
                add(
                    AdvisoryAlert(
                        title = "CPU ABI has limited native hook support",
                        detail = cpu.message.ifBlank {
                            "The module may load, but active audio hooks are not enabled for " +
                                cpu.zygiskAbi.ifBlank { "this ABI" } + "."
                        },
                        category = "Hardware compatibility"
                    )
                )
            }
            Unit
        }
        moduleStatus?.audioStack?.let { stack ->
            if (stack.vendorFamily.equals("Unknown", ignoreCase = true) ||
                stack.vendorFamily.contains("unclassified", ignoreCase = true)
            ) {
                add(
                    AdvisoryAlert(
                        title = "Vendor audio family is not classified",
                        detail = "HAL label ${stack.hal.ifBlank { "unknown" }} did not match " +
                            "known emulator, Qualcomm, MediaTek, Exynos, or Tensor patterns.",
                        category = "Hardware compatibility"
                    )
                )
            }
            if (!stack.aaudioSupported) {
                add(
                    AdvisoryAlert(
                        title = "AAudio low-latency path unavailable",
                        detail = "This device did not report native AAudio support. Echidna can still try " +
                            "fallback hooks, but latency and app coverage may vary.",
                        category = "Hardware compatibility"
                    )
                )
            }
            if (!stack.openSlEsAvailable) {
                add(
                    AdvisoryAlert(
                        title = "OpenSL ES library not found",
                        detail = "The compatibility probe could not find libOpenSLES.so in common " +
                            "system or vendor paths. OpenSL hook coverage is unlikely on this image.",
                        category = "Hardware compatibility"
                    )
                )
            }
            if (!stack.audioFlingerClientAvailable) {
                add(
                    AdvisoryAlert(
                        title = "AudioFlinger client library not found",
                        detail = "The compatibility probe could not find libaudioclient.so in common " +
                            "system or vendor paths. AudioFlinger client hook coverage is unlikely.",
                        category = "Hardware compatibility"
                    )
                )
            }
            if (!stack.tinyAlsaAvailable) {
                add(
                    AdvisoryAlert(
                        title = "tinyalsa library not found",
                        detail = "The compatibility probe could not find libtinyalsa.so in common " +
                            "system or vendor paths. tinyalsa/HAL fallback coverage is unlikely.",
                        category = "Hardware compatibility"
                    )
                )
            }
            if (!stack.lowLatency) {
                add(
                    AdvisoryAlert(
                        title = "Low-latency audio feature absent",
                        detail = "Android does not report FEATURE_AUDIO_LOW_LATENCY. Calls and live " +
                            "monitoring may need balanced or compatibility mode.",
                        category = "Hardware compatibility"
                    )
                )
            }
            if (!stack.proAudio) {
                add(
                    AdvisoryAlert(
                        title = "Pro audio feature absent",
                        detail = "Android does not report FEATURE_AUDIO_PRO. Echidna remains usable, " +
                            "but device routing may not be tuned for stable low-latency capture.",
                        category = "Hardware compatibility"
                    )
                )
            }
            if (stack.hal.isBlank() || stack.hal.equals("unknown", ignoreCase = true)) {
                add(
                    AdvisoryAlert(
                        title = "Audio HAL could not be identified",
                        detail = "Vendor audio routing is unknown. Validate the target apps manually " +
                            "before using the native hook path.",
                        category = "Hardware compatibility"
                    )
                )
            }
            if (stack.sampleRate <= 0 || stack.framesPerBuffer <= 0) {
                add(
                    AdvisoryAlert(
                        title = "Incomplete audio stack probe",
                        detail = "Sample rate or buffer size was not reported. This can indicate an " +
                            "incomplete bridge or a vendor HAL that hides useful diagnostics.",
                        category = "Hardware compatibility"
                    )
                )
            }
        }
        if (telemetry.averageLatencyMs > settings.alertLatencyThresholdMs) {
            add(
                AdvisoryAlert(
                    title = "High processing latency",
                    detail = "Telemetry average is ${telemetry.averageLatencyMs.roundToInt()} ms, above " +
                        "the configured ${settings.alertLatencyThresholdMs} ms alert threshold.",
                    category = "Runtime performance"
                )
            )
        }
        if (telemetry.xruns >= settings.alertXrunThreshold) {
            add(
                AdvisoryAlert(
                    title = "Audio XRuns detected",
                    detail = "Telemetry reports ${telemetry.xruns} XRuns. Reduce DSP load, use bypass, " +
                        "or switch latency mode if audio glitches.",
                    category = "Runtime performance"
                )
            )
        }
        compatibility?.audioStack
            ?.filter { !it.supported }
            ?.filterNot { probe ->
                moduleStatus != null &&
                    (probe.name.contains("AAudio", ignoreCase = true) ||
                        probe.name.contains("Low-latency", ignoreCase = true) ||
                        probe.name.contains("Pro audio", ignoreCase = true))
            }
            ?.take(3)
            ?.forEach { probe ->
                add(
                    AdvisoryAlert(
                        title = "${probe.name} probe is unsupported",
                        detail = probe.message.compactForAlert(),
                        category = "Hardware compatibility"
                    )
                )
            }
        if (telemetry.warnings.isNotEmpty()) {
            add(
                AdvisoryAlert(
                    title = "Runtime telemetry has warnings",
                    detail = telemetry.warnings.joinToString("; ").compactForAlert(),
                    category = "Runtime performance"
                )
            )
        }
    }

    if (settings.showInstallMixupAlerts) {
        moduleStatus?.let { status ->
            if (status.magiskModuleInstalled && !status.zygiskEnabled) {
                add(
                    AdvisoryAlert(
                        title = "Magisk module present but Zygisk is not active",
                        detail = "The module package appears installed, but the expected Zygisk " +
                            "bridge is disabled or not visible after boot.",
                        category = "Install mix-up"
                    )
                )
            }
            if (status.zygiskEnabled && status.javaFallbackRecommended) {
                add(
                    AdvisoryAlert(
                        title = "Native capture route remains unverified",
                        detail = "Zygisk availability does not prove audio buffers were transformed. " +
                            "Use LSPosed compatibility mode only for targets assigned to that owner.",
                        category = "Install mix-up"
                    )
                )
            }
            if (!status.zygiskEnabled && status.javaFallbackRecommended) {
                add(
                    AdvisoryAlert(
                        title = "LSPosed compatibility mode recommended",
                        detail = "No native route is verified. LSPosed may cover selected AudioRecord " +
                            "targets after its scope and capture owner are configured.",
                        category = "Install mix-up"
                    )
                )
            }
            if (!status.magiskModuleInstalled && status.javaFallbackRecommended) {
                add(
                    AdvisoryAlert(
                        title = "Native module missing; fallback is only a recommendation",
                        detail = "The app has not verified an active LSPosed route. Install and scope " +
                            "the shim before relying on Java AudioRecord coverage.",
                        category = "Install mix-up"
                    )
                )
            }
        }
        if (settings.engineMode == DspEngineMode.COMPATIBILITY && engineStatus.nativeInstalled) {
            add(
                AdvisoryAlert(
                    title = "Compatibility mode selected with native module present",
                    detail = "This is allowed, but native hooks may be intentionally de-emphasized. " +
                        "Switch modes if you expected the native-first path.",
                    category = "Install mix-up"
                )
            )
        }
    }
}

private fun String.containsAdvisoryWord(): Boolean {
    val lower = lowercase()
    return listOf(
        "absent",
        "denied",
        "disabled",
        "error",
        "fail",
        "missing",
        "not installed",
        "partial",
        "unavailable",
        "unbound",
        "unknown",
        "unsupported",
        "warning"
    ).any(lower::contains)
}

private fun WhitelistBindings.enabledCount(): Int = whitelist.count { it.value }

private fun String.compactForAlert(maxLength: Int = 220): String =
    if (length <= maxLength) this else take(maxLength - 3).trimEnd() + "..."
