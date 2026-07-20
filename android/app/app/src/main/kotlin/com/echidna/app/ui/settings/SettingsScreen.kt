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
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echidna.app.model.AccentColor
import com.echidna.app.model.DspEngineMode
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.LegacyPreprocessorControlState
import com.echidna.app.model.SettingsProfile
import com.echidna.app.model.SettingsState
import com.echidna.app.model.ThemeMode
import com.echidna.app.ui.theme.dynamicColorSupported
import com.echidna.app.ui.theme.echidnaColorScheme
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onLaunchCompatibility: () -> Unit,
    onLaunchWhitelist: () -> Unit,
    onLaunchInstaller: () -> Unit,
    onOpenAlerts: () -> Unit,
    onRunSetupAgain: () -> Unit = {},
    // Opens the in-app Help & Docs screen. Defaulted so this is a purely additive entry point.
    onOpenHelp: () -> Unit = {}
) {
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val defaultId by viewModel.defaultPresetId.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val profiles by viewModel.settingsProfiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeSettingsProfileId.collectAsStateWithLifecycle()
    val moduleStatus by viewModel.moduleStatus.collectAsStateWithLifecycle()
    val legacyPreprocessor by viewModel.legacyPreprocessorState.collectAsStateWithLifecycle()

    var newProfileName by remember { mutableStateOf("") }
    var selectedProfileId by remember { mutableStateOf<String?>(null) }
    var importJson by remember { mutableStateOf("") }
    var exportJson by remember { mutableStateOf("") }
    var profileMessage by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(SettingsTab.ALERTS) }

    // An alert action can hand off "open Settings on the Engine tab" so the user lands on DSP
    // engine mode itself rather than on Settings and a hunt. The request is consumed once.
    LaunchedEffect(Unit) {
        when (SettingsFocusRequest.consume()) {
            SettingsFocus.ENGINE -> selectedTab = SettingsTab.ENGINE
            null -> Unit
        }
    }

    LaunchedEffect(profiles, activeProfileId) {
        if (selectedProfileId == null || profiles.none { it.id == selectedProfileId }) {
            selectedProfileId = activeProfileId ?: profiles.firstOrNull()?.id
        }
    }

    val defaultPresetName = presets.firstOrNull { it.id == defaultId }?.name ?: "Unknown"
    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }

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

        HelpDocsLinkSection(onOpenHelp = onOpenHelp)

        when (selectedTab) {
            SettingsTab.APPEARANCE -> {
                AppearanceSection(
                    settings = settings,
                    onThemeMode = viewModel::setThemeMode,
                    onDynamicColor = viewModel::setDynamicColor,
                    onAccentColor = viewModel::setAccentColor,
                    onKeepScreenOn = viewModel::setKeepScreenOn
                )
            }

            SettingsTab.ALERTS -> {
                AlertsLinkSection(onOpenAlerts = onOpenAlerts)
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
                    onLaunchWhitelist = onLaunchWhitelist,
                    onRunSetupAgain = onRunSetupAgain
                )
                NotificationSection(
                    settings = settings,
                    onPersistentNotification = viewModel::setPersistentNotification,
                    onQuickControls = viewModel::setQuickControlsEnabled,
                    onWidgetControls = viewModel::setWidgetControlsEnabled,
                    onHighPriorityNotification = viewModel::setHighPriorityNotification
                )
            }

            SettingsTab.ENGINE -> {
                EngineModuleSection(
                    moduleInstalled = moduleStatus?.magiskModuleInstalled == true,
                    onLaunchInstaller = onLaunchInstaller
                )
                EngineSection(
                    settings = settings,
                    onEngineMode = viewModel::setDspEngineMode,
                    onLatencyMode = viewModel::setLatencyMode,
                    onSidetoneEnabled = viewModel::setSidetoneEnabled,
                    onSidetoneLevel = viewModel::setSidetoneLevel
                )
                LegacyPreprocessorSection(
                    state = legacyPreprocessor,
                    onEnabledChange = viewModel::setLegacyPreprocessorEnabled,
                )
            }

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
                onVerboseLogging = viewModel::setVerboseLogging,
                onStatusPollInterval = viewModel::setStatusPollIntervalSeconds
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
    APPEARANCE("Appearance"),
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
private fun AlertsLinkSection(onOpenAlerts: () -> Unit) {
    // The live advisory alerts moved to the dedicated top-level Alerts tab. Settings keeps only the
    // preferences that decide which advisories are computed; this row links to the tab itself.
    SettingsSection(title = "Advisory Alerts") {
        Text(
            text = "Live install, bridge, hardware, and hook-scope advisories now live on the " +
                "Alerts tab, where each can be dismissed or permanently silenced with " +
                "\"Don't remind\". The preferences below decide which of them are shown.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onOpenAlerts, modifier = Modifier.fillMaxWidth()) {
            Text("Open Alerts")
        }
    }
}

@Composable
private fun HelpDocsLinkSection(onOpenHelp: () -> Unit) {
    // Second, always-visible entry point to the in-app Help (the first is the top-app-bar action).
    SettingsSection(title = "Help & Docs") {
        Text(
            text = "Browse the bundled project documentation offline, with full-text search across " +
                "every doc — architecture, verification, limitations, and the hardening notes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onOpenHelp, modifier = Modifier.fillMaxWidth()) {
            Text("Open Help & Docs")
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
private fun StartupSection(
    settings: SettingsState,
    onStartWithSystem: (Boolean) -> Unit,
    onAutoStartEngine: (Boolean) -> Unit,
    onRestoreLastProfile: (Boolean) -> Unit,
    onLaunchCompatibility: () -> Unit,
    onLaunchWhitelist: () -> Unit,
    onRunSetupAgain: () -> Unit
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
        OutlinedButton(
            onClick = onRunSetupAgain,
            modifier = Modifier.fillMaxWidth().testTag("settings_run_setup_again"),
        ) {
            Text("Run setup again")
        }
    }
}

@Composable
private fun EngineModuleSection(
    moduleInstalled: Boolean,
    onLaunchInstaller: () -> Unit
) {
    SettingsSection(title = "Engine module") {
        Text(
            text = if (moduleInstalled) {
                "The Echidna Magisk/Zygisk engine module is installed. Open the installer to update " +
                    "or remove it."
            } else {
                "The Echidna engine module is not installed. Open the guided installer to set it up " +
                    "(requires root with Magisk and Zygisk)."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onLaunchInstaller, modifier = Modifier.fillMaxWidth()) {
            Icon(imageVector = Icons.Filled.Download, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (moduleInstalled) "Install or update engine" else "Install engine")
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
internal fun LegacyPreprocessorSection(
    state: LegacyPreprocessorControlState,
    onEnabledChange: (Boolean) -> Unit,
) {
    val title = "Legacy AudioFlinger preprocessor (experimental)"
    val status = when {
        state.updating && !state.loaded -> "Loading persisted state…"
        state.updating -> "Saving and confirming…"
        !state.loaded -> "Unavailable until the control service connects"
        !state.available -> if (state.enabled) {
            "Unavailable — last confirmed enabled"
        } else {
            "Unavailable — last confirmed disabled"
        }
        state.enabled -> "Attachment permission enabled"
        else -> "Attachment permission disabled (default)"
    }
    val accessibilityState = when {
        state.updating -> "Updating; last confirmed ${if (state.enabled) "on" else "off"}"
        !state.available -> "Unavailable; last confirmed ${if (state.enabled) "on" else "off"}"
        state.enabled -> "On; confirmed by control service"
        else -> "Off; confirmed by control service"
    }

    SettingsSection(title = "Experimental Capture Attachment") {
        ToggleRow(
            title = title,
            description = "Permit LSPosed to request authorized attachment for individual " +
                "AudioRecord sessions. Enabling this does not prove the effect is loaded, " +
                "active, or processing audio.",
            checked = state.enabled,
            enabled = state.loaded && state.available && !state.updating,
            stateDescription = accessibilityState,
            onCheckedChange = onEnabledChange,
        )
        StatusPill(text = status)
        state.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = "Active processing still requires staged signer trust and effect registration " +
                "from a prior boot, a restart, a supported legacy HIDL effects factory, LSPosed " +
                "for the target app, and fresh route-matched mutation proof.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "This switch is available only from a companion installed in Android user 0, " +
                "and authorization is limited to trusted, explicitly whitelisted user 0 targets " +
                "with the LSPosed capture owner. Device behavior remains unproven; " +
                "Stable-AIDL-only devices are unsupported. The switch itself is not an SDK-level " +
                "compatibility verdict; runtime HIDL and effect evidence determine eligibility.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiagnosticsSection(
    settings: SettingsState,
    onDebugMode: (Boolean) -> Unit,
    onTelemetryOptIn: (Boolean) -> Unit,
    onVerboseLogging: (Boolean) -> Unit,
    onStatusPollInterval: (Int) -> Unit
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
        Text(
            text = "Status poll interval ${settings.statusPollIntervalSeconds} s",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "How often the app polls the control service for telemetry, module, and " +
                "SELinux/HAL status. Lower is more responsive; higher saves battery.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = settings.statusPollIntervalSeconds.toFloat(),
            onValueChange = { onStatusPollInterval(it.roundToInt()) },
            valueRange = 1f..10f,
            steps = 8
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(
    settings: SettingsState,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onAccentColor: (AccentColor) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit
) {
    val dynamicSupported = dynamicColorSupported()
    val dynamicActive = dynamicSupported && settings.dynamicColor
    SettingsSection(title = "Theme") {
        Text(text = "Theme mode", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            enumValues<ThemeMode>().forEach { mode ->
                ChoiceButton(
                    label = mode.label,
                    selected = settings.themeMode == mode,
                    onClick = { onThemeMode(mode) }
                )
            }
        }
        Text(
            text = "System follows your device's light/dark setting. Light and Dark force the app " +
                "regardless of the system.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ToggleRow(
            title = "Dynamic color (Material You)",
            description = if (dynamicSupported) {
                "Tint the app from your wallpaper palette. Turn off to pick a fixed accent below."
            } else {
                "Requires Android 12 or newer. This device uses the fixed accent below instead."
            },
            checked = dynamicActive,
            enabled = dynamicSupported,
            onCheckedChange = onDynamicColor
        )

        Text(text = "Accent color", style = MaterialTheme.typography.titleSmall)
        Text(
            text = if (dynamicActive) {
                "Dynamic color is on, so the wallpaper palette is used instead of a fixed accent."
            } else {
                "Used for the app's Light and Dark schemes."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val previewDark = settings.themeMode.let { mode ->
            when (mode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            enumValues<AccentColor>().forEach { accent ->
                AccentSwatch(
                    label = accent.label,
                    color = echidnaColorScheme(accent, previewDark).primary,
                    selected = !dynamicActive && settings.accentColor == accent,
                    enabled = !dynamicActive,
                    onClick = { onAccentColor(accent) }
                )
            }
        }

        ToggleRow(
            title = "Keep screen on",
            description = "Prevent the display from sleeping while Echidna is in the foreground.",
            checked = settings.keepScreenOn,
            onCheckedChange = onKeepScreenOn
        )
    }
}

@Composable
private fun AccentSwatch(
    label: String,
    color: Color,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.outline
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .semantics {
                contentDescription = label
                stateDescription = if (selected) "Selected" else "Not selected"
            }
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled, onClick = onClick)
                .background(if (enabled) color else color.copy(alpha = 0.4f))
                .border(if (selected) 3.dp else 1.dp, borderColor, CircleShape)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun NotificationSection(
    settings: SettingsState,
    onPersistentNotification: (Boolean) -> Unit,
    onQuickControls: (Boolean) -> Unit,
    onWidgetControls: (Boolean) -> Unit,
    onHighPriorityNotification: (Boolean) -> Unit
) {
    SettingsSection(title = "Notification and Control") {
        ToggleRow(
            title = "Persistent notification",
            description = "Keep quick toggles available in the status bar.",
            checked = settings.persistentNotification,
            onCheckedChange = onPersistentNotification
        )
        ToggleRow(
            title = "High-priority notification",
            description = "Raise the controls channel from silent to default importance. Android " +
                "rebuilds the channel on change and may keep a manual customization you set later.",
            checked = settings.highPriorityNotification,
            enabled = settings.persistentNotification,
            onCheckedChange = onHighPriorityNotification
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
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    stateDescription: String = if (checked) "On" else "Off",
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = title
                this.stateDescription = stateDescription
            },
        )
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
