package com.echidna.app.ui.onboarding

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echidna.app.R
import com.echidna.app.audio.EchidnaLabDsp
import com.echidna.app.model.AccentColor
import com.echidna.app.model.CompatibilityResult
import com.echidna.app.model.ThemeMode
import com.echidna.app.system.MagiskLauncher
import com.echidna.app.ui.theme.dynamicColorSupported
import com.echidna.app.ui.theme.echidnaColorScheme

/**
 * Renders the content for a single wizard step. Kept free of the wizard chrome (progress bar,
 * back/next) which lives in [OnboardingWizardHost]. Each step reads/writes state through the
 * reused repository actions on [OnboardingViewModel] and never invents its own persistence.
 */
@Composable
fun OnboardingStepContent(
    step: OnboardingStep,
    viewModel: OnboardingViewModel,
    onOpenInstaller: () -> Unit,
    onOpenLab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.testTag(OnboardingTestTags.step(step))) {
        when (step) {
            OnboardingStep.WELCOME -> WelcomeStep()
            OnboardingStep.PERMISSIONS -> PermissionsStep()
            OnboardingStep.COMPATIBILITY -> CompatibilityStep(viewModel)
            OnboardingStep.RECOVERY -> RecoveryStep(viewModel)
            OnboardingStep.THEME -> ThemeStep(viewModel)
            OnboardingStep.PRESET -> PresetStep(viewModel)
            OnboardingStep.WHITELIST -> WhitelistStep(viewModel)
            OnboardingStep.ALERTS -> AlertsStep(viewModel)
            OnboardingStep.HIGH_PRIORITY_NOTIFICATION -> HighPriorityStep(viewModel)
            OnboardingStep.QUICK_TILE -> QuickTileStep(viewModel)
            OnboardingStep.ENGINE -> EngineStep(viewModel, onOpenInstaller)
            OnboardingStep.LAB -> LabStep(onOpenLab)
            OnboardingStep.DONE -> DoneStep(viewModel)
        }
    }
}

// --- 1. Welcome -------------------------------------------------------------------------------

@Composable
private fun WelcomeStep() {
    Text(
        "Echidna is a real-time voice/audio transformer for Android. It is experimental software.",
        style = MaterialTheme.typography.bodyLarge,
    )
    OnboardingGap()
    OnboardingCard(title = "What actually works") {
        OnboardingBullet(
            "The Lab tab works with no root: record or generate a tone and hear a preset applied " +
                "offline. That path needs only microphone access."
        )
        OnboardingBullet(
            "System-wide interception (transforming audio inside other apps and calls) needs a " +
                "rooted device with Magisk + Zygisk and the Echidna engine module installed."
        )
        OnboardingBullet(
            "Even with root, many devices remain unsupported. Installing the app never proves your " +
                "device can intercept audio — the compatibility check and the engine status tell the truth."
        )
    }
    OnboardingGap()
    Text(
        "This wizard sets sane defaults. Every step is optional — skip anything and change it later " +
            "in Settings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// --- 2. Permissions ---------------------------------------------------------------------------

@Composable
private fun PermissionsStep() {
    val context = LocalContext.current

    fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun notificationsGranted(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }

    var micState by remember { mutableStateOf(micGranted()) }
    var notifState by remember { mutableStateOf(notificationsGranted()) }

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> micState = granted }
    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> notifState = granted }

    Text(
        "Echidna asks for two permissions. You can deny either and still continue — the app just " +
            "degrades honestly.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OnboardingGap()
    PermissionRow(
        title = "Microphone",
        rationale = "Needed for the Lab tab (record/monitor) and any live monitoring.",
        granted = micState,
        onRequest = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        OnboardingGap()
        PermissionRow(
            title = "Notifications",
            rationale = "Android 13+ requires this for the controls notification to appear at all.",
            granted = notifState,
            onRequest = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        )
    } else {
        OnboardingGap()
        Text(
            "Notification permission is automatic on this Android version.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    rationale: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    OnboardingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    rationale,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (granted) {
                StatusTag(text = "Granted", tone = Tone.PASS)
            } else {
                OutlinedButton(onClick = onRequest) { Text("Grant") }
            }
        }
        if (!granted) {
            OnboardingGap(6)
            Text(
                "Denied for now — that's fine, this step is optional.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- 3. Compatibility -------------------------------------------------------------------------

@Composable
private fun CompatibilityStep(viewModel: OnboardingViewModel) {
    val result by viewModel.compatibility.collectAsStateWithLifecycle()
    // Kick off a fresh probe when this step first appears; the repository de-dupes/serializes it.
    LaunchedEffect(Unit) { viewModel.runCompatibilityProbe() }

    Text(
        "This runs the same compatibility probes as the Compatibility Wizard so you know your " +
            "device's honest capability up front.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OnboardingGap()
    val current = result
    if (current == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.width(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("Probing device…", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        CompatibilitySummary(current)
    }
    OnboardingGap()
    Text(
        "You can re-run the full Compatibility Wizard any time from Settings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CompatibilitySummary(result: CompatibilityResult) {
    val (pass, warn, fail) = compatCounts(result)
    val overall = when {
        fail > 0 -> Tone.FAIL
        warn > 0 -> Tone.WARN
        else -> Tone.PASS
    }
    OnboardingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToneIcon(overall)
            Spacer(Modifier.width(8.dp))
            Text(
                when (overall) {
                    Tone.PASS -> "Looks capable"
                    Tone.WARN -> "Some warnings"
                    Tone.FAIL -> "Significant gaps"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        OnboardingGap(6)
        Text(
            "$pass pass · $warn warn · $fail fail",
            style = MaterialTheme.typography.bodyMedium,
        )
        OnboardingGap(6)
        Text(
            "SELinux: ${result.selinuxStatus}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Compact pass/warn/fail tally mirroring the Compatibility Wizard's overview tone rules. */
private fun compatCounts(result: CompatibilityResult): Triple<Int, Int, Int> {
    var pass = 0
    var warn = 0
    var fail = 0
    val selinux = result.selinuxStatus.lowercase()
    when {
        listOf("unknown", "unavailable", "error", "denied").any { selinux.contains(it) } -> fail++
        listOf("permissive", "warning", "partial").any { selinux.contains(it) } -> warn++
        else -> pass++
    }
    result.audioStack.forEach { probe ->
        when {
            probe.name.lowercase().contains("control service") -> fail++
            probe.supported -> pass++
            else -> warn++
        }
    }
    return Triple(pass, warn, fail)
}

// --- 4. Recovery ------------------------------------------------------------------------------

@Composable
private fun RecoveryStep(viewModel: OnboardingViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Text(
        "Before any engine-install step: if a root module ever bootloops your phone, you must be " +
            "able to disable it without a normal boot.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OnboardingGap()
    OnboardingCard(title = "Your recovery plan") {
        OnboardingBullet("Keep a full backup and a known-good recovery path for your device.")
        OnboardingBullet(
            "The engine stands down when a disable marker exists, e.g. " +
                "/data/adb/modules/echidna/disable or /data/adb/echidna/disable."
        )
        OnboardingBullet(
            "Boot into safe mode or recovery + adb to create that marker if the phone won't boot " +
                "normally. Full steps are in docs/recovery.md."
        )
    }
    OnboardingGap()
    val ack = state.recoveryAcknowledged
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = ack,
            onCheckedChange = viewModel::acknowledgeRecovery,
            modifier = Modifier.testTag(OnboardingTestTags.RECOVERY_ACK),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "I understand how to recover if a root module bootloops my device.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clickable { viewModel.acknowledgeRecovery(!ack) },
        )
    }
    if (!ack) {
        Text(
            "Acknowledge to continue. (This is the one step you can't skip past.)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

// --- 5. Theme ---------------------------------------------------------------------------------

@Composable
private fun ThemeStep(viewModel: OnboardingViewModel) {
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val dynamicActive = dynamicColorSupported() && settings.dynamicColor

    Text("Pick a theme. Changes apply immediately.", style = MaterialTheme.typography.bodyMedium)
    OnboardingGap()
    Text("Mode", style = MaterialTheme.typography.titleSmall)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemeMode.entries.forEach { mode ->
            OnboardingChoiceButton(
                label = mode.label,
                selected = settings.themeMode == mode,
                onClick = { viewModel.setThemeMode(mode) },
            )
        }
    }
    OnboardingGap()
    if (dynamicColorSupported()) {
        OnboardingToggleRow(
            title = "Material You colors",
            description = "Use the system dynamic palette (Android 12+). Turn off to pick an accent.",
            checked = settings.dynamicColor,
            onCheckedChange = viewModel::setDynamicColor,
        )
    }
    OnboardingGap()
    Text(
        if (dynamicActive) "Accent (disabled while Material You is on)" else "Accent",
        style = MaterialTheme.typography.titleSmall,
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        AccentColor.entries.forEach { accent ->
            OnboardingAccentSwatch(
                label = accent.label,
                color = echidnaColorScheme(accent, settings.themeMode.let {
                    when (it) {
                        ThemeMode.LIGHT -> false
                        ThemeMode.DARK -> true
                        ThemeMode.SYSTEM -> systemDark
                    }
                }).primary,
                selected = !dynamicActive && settings.accentColor == accent,
                enabled = !dynamicActive,
                onClick = { viewModel.setAccentColor(accent) },
            )
        }
    }
}

// --- 6. Preset --------------------------------------------------------------------------------

@Composable
private fun PresetStep(viewModel: OnboardingViewModel) {
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val active by viewModel.activePreset.collectAsStateWithLifecycle()

    Text(
        "Choose the preset to start with. It becomes your active preset — you can add and edit " +
            "presets later.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OnboardingGap()
    presets.forEach { preset ->
        val selected = preset.id == active.id
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = selected, onValueChange = { viewModel.selectPreset(preset.id) })
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = { viewModel.selectPreset(preset.id) })
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(preset.name, style = MaterialTheme.typography.titleSmall)
                preset.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// --- 7. Whitelist -----------------------------------------------------------------------------

@Composable
private fun WhitelistStep(viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    val bindings by viewModel.whitelistBindings.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

    LaunchedEffect(Unit) {
        val pm = context.packageManager
        val packages = viewModel.installedLaunchablePackages()
        apps = packages.map { pkg ->
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            pkg to label
        }.sortedBy { it.second.lowercase() }.take(40)
    }

    Text(
        "Pick the apps Echidna should target once the engine is installed. This only matters with " +
            "root + engine; it's harmless to set now.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OnboardingGap()
    val current = apps
    when {
        current == null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.width(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("Loading installed apps…", style = MaterialTheme.typography.bodyMedium)
        }
        current.isEmpty() -> Text(
            "No launchable apps found to suggest.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> current.forEach { (pkg, label) ->
            val enabled = bindings.whitelist[pkg] == true
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = enabled,
                        onValueChange = { viewModel.updateWhitelist(pkg, it) },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = enabled, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        pkg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// --- 8. Alerts --------------------------------------------------------------------------------

@Composable
private fun AlertsStep(viewModel: OnboardingViewModel) {
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    // The app has four advisory categories; treat "all on" as the master's checked state.
    val allOn = settings.showInstallAlerts && settings.showBridgeAlerts &&
        settings.showHardwareAlerts && settings.showInstallMixupAlerts

    Text(
        "Advisory alerts warn you about install problems, bridge/engine issues, hardware limits, " +
            "and risky module mix-ups. They're on by default.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OnboardingGap()
    Column(modifier = Modifier.testTag(OnboardingTestTags.ALERTS_TOGGLE)) {
        OnboardingToggleRow(
            title = "Show advisory alerts",
            description = "Turn all four advisory categories on or off. Fine-tune them later in Settings.",
            checked = allOn,
            onCheckedChange = viewModel::setAlertsEnabled,
        )
    }
}

// --- 9. High-priority notification ------------------------------------------------------------

@Composable
private fun HighPriorityStep(viewModel: OnboardingViewModel) {
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()

    Text(
        "The controls notification lets you toggle Echidna without opening the app.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OnboardingGap()
    OnboardingToggleRow(
        title = "Controls notification",
        description = "Keep a persistent notification with quick controls.",
        checked = settings.persistentNotification,
        onCheckedChange = viewModel::setPersistentNotification,
    )
    Column(modifier = Modifier.testTag(OnboardingTestTags.HIGH_PRIORITY_TOGGLE)) {
        OnboardingToggleRow(
            title = "High-priority notification",
            // Reuses the exact honest wording from Settings.
            description = "Raise the controls channel from silent to default importance. Android " +
                "rebuilds the channel on change and may keep a manual customization you set later.",
            checked = settings.highPriorityNotification,
            enabled = settings.persistentNotification,
            onCheckedChange = viewModel::setHighPriorityNotification,
        )
    }
    if (!settings.persistentNotification) {
        Text(
            "Enable the controls notification above for the high-priority option to matter.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- 10. Quick Settings tile ------------------------------------------------------------------

@Composable
private fun QuickTileStep(viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    var addResult by remember { mutableStateOf<String?>(null) }

    Text(
        "Add Echidna's Quick Settings tile to toggle it from the notification shade.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OnboardingGap()
    Column(modifier = Modifier.testTag(OnboardingTestTags.QUICK_TILE_TOGGLE)) {
        OnboardingToggleRow(
            title = "Enable the Quick Settings tile",
            description = "When off, the tile shows as unavailable.",
            checked = settings.quickControlsEnabled,
            onCheckedChange = viewModel::setQuickControlsEnabled,
        )
    }
    OnboardingGap()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        OutlinedButton(
            onClick = {
                addResult = requestAddTile(context)
            },
            enabled = settings.quickControlsEnabled,
        ) { Text("Add tile now") }
        addResult?.let {
            OnboardingGap(6)
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        OnboardingCard {
            Text(
                "On this Android version, add the tile manually: pull down Quick Settings, tap the " +
                    "edit (pencil) button, and drag the Echidna tile into place.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Programmatic one-tap tile add (API 33+); returns an honest result string. */
private fun requestAddTile(context: android.content.Context): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return "Not supported on this Android version."
    }
    val sbm = context.getSystemService(StatusBarManager::class.java)
        ?: return "Quick Settings service unavailable."
    return runCatching {
        sbm.requestAddTileService(
            ComponentName(context, "com.echidna.app.system.EchidnaQuickSettingsTileService"),
            "Echidna",
            android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_echidna_mono),
            context.mainExecutor,
            {},
        )
        "Requested — confirm the system prompt if it appears."
    }.getOrElse { "Couldn't request the tile add; add it manually from Quick Settings." }
}

// --- 11. Engine install -----------------------------------------------------------------------

@Composable
private fun EngineStep(viewModel: OnboardingViewModel, onOpenInstaller: () -> Unit) {
    val context = LocalContext.current
    val status by viewModel.moduleStatus.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshModuleStatus() }

    val installed = status?.magiskModuleInstalled == true
    val zygisk = status?.zygiskEnabled == true
    val detected = installed || zygisk

    Text(
        "System-wide interception needs the Echidna engine module (Magisk + Zygisk). This is " +
            "optional — skip it if you're not rooted or want to do it later.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OnboardingGap()
    OnboardingCard(title = "Detection") {
        DetectRow("Zygisk enabled", zygisk)
        DetectRow("Echidna module installed", installed)
    }
    OnboardingGap()
    if (detected) {
        Text(
            "Magisk/Zygisk detected. Open the guided installer to install or update the engine.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OnboardingGap()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val intent = MagiskLauncher.resolveLaunchIntent(context.packageManager)
                if (intent != null) runCatching { context.startActivity(intent) }
            }) { Text("Open Magisk") }
            OutlinedButton(onClick = onOpenInstaller) { Text("Open installer") }
        }
    } else {
        OnboardingCard {
            Text(
                "No Magisk/Zygisk detected. You can still use the Lab tab without root, and install " +
                    "the engine later from Settings if you root this device.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DetectRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToneIcon(if (ok) Tone.PASS else Tone.WARN)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            if (ok) "yes" else "no",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- 12. Lab ----------------------------------------------------------------------------------

@Composable
private fun LabStep(onOpenLab: () -> Unit) {
    val engineAvailable = remember { EchidnaLabDsp.engineAvailable() }

    Text(
        "The best first experience is actually hearing a transform. The Lab applies a preset to a " +
            "test tone or your recording, entirely on-device.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OnboardingGap()
    if (engineAvailable) {
        OnboardingCard {
            Text(
                "DSP engine: loaded. Open the Lab, generate a tone or record, then compare dry vs. " +
                    "processed to hear it work.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OnboardingGap()
        OutlinedButton(onClick = onOpenLab) { Text("Open the Lab") }
    } else {
        OnboardingCard {
            Text(
                "DSP engine: unavailable (lite build). This build can't process audio locally — " +
                    "rebuild with the native engine to hear transforms. You can still explore the Lab UI.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OnboardingGap()
        OutlinedButton(onClick = onOpenLab) { Text("Open the Lab anyway") }
    }
}

// --- 13. Done ---------------------------------------------------------------------------------

@Composable
private fun DoneStep(viewModel: OnboardingViewModel) {
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val active by viewModel.activePreset.collectAsStateWithLifecycle()

    Text("You're set up.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    OnboardingGap()
    OnboardingCard(title = "Summary") {
        SummaryLine("Theme", settings.themeMode.label)
        SummaryLine("Active preset", active.name)
        SummaryLine(
            "Advisory alerts",
            if (settings.showInstallAlerts || settings.showBridgeAlerts ||
                settings.showHardwareAlerts || settings.showInstallMixupAlerts
            ) "on" else "off",
        )
        SummaryLine("Controls notification", if (settings.persistentNotification) "on" else "off")
        SummaryLine("Quick Settings tile", if (settings.quickControlsEnabled) "enabled" else "disabled")
    }
    OnboardingGap()
    Text(
        "You can re-run this setup any time from Settings → Run setup again.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

// --- Shared status atoms ----------------------------------------------------------------------

private enum class Tone { PASS, WARN, FAIL }

@Composable
private fun ToneIcon(tone: Tone) {
    val (icon, color) = when (tone) {
        Tone.PASS -> Icons.Filled.CheckCircle to Color(0xFF4CAF50)
        Tone.WARN -> Icons.Filled.Warning to Color(0xFFFFB300)
        Tone.FAIL -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Icon(imageVector = icon, contentDescription = tone.name, tint = color)
}

@Composable
private fun StatusTag(text: String, tone: Tone) {
    Row(
        modifier = Modifier.wrapContentWidth().semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToneIcon(tone)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}
