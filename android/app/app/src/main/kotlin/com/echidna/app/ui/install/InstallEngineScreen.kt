package com.echidna.app.ui.install

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echidna.app.system.MagiskLauncher
import com.echidna.app.ui.components.AlertSeverity
import com.echidna.app.ui.components.PersistentDismissibleAlert
import com.echidna.app.ui.components.rememberDismissedAlertsStore

private val GreenAccent = Color(0xFF4CAF50)
private val AmberAccent = Color(0xFFFFB300)

/** Stable namespace prefix for the install-screen root-module risk dismissals. */
private const val INSTALL_RISK_KEY_PREFIX = "install.risk:"

/**
 * Guided install / uninstall of the Echidna Magisk/Zygisk engine from inside the companion.
 * Honest by construction: root/Magisk availability is derived from the real privileged probe, and
 * success is only shown after the module is confirmed via the status poll.
 */
@Composable
fun InstallEngineScreen(
    viewModel: InstallEngineViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) viewModel.installFromUri(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Install engine",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        RiskCard(moduleInstalled = state.moduleInstalled)

        DetectionCard(state = state)

        if (state.busy) {
            BusyCard(state = state)
        }

        state.message?.takeIf { !state.busy }?.let { message ->
            MessageCard(phase = state.phase, message = message)
        }

        OpenMagiskButton()

        when (state.phase) {
            InstallPhase.INSTALL_REBOOT, InstallPhase.UNINSTALL_REBOOT -> {
                RebootCard(
                    removing = state.phase == InstallPhase.UNINSTALL_REBOOT,
                    onReboot = viewModel::reboot,
                    onRecheck = viewModel::refresh,
                )
            }
            else -> {
                ActionsCard(
                    state = state,
                    onInstall = viewModel::install,
                    onPickZip = { zipPicker.launch(ZIP_MIME) },
                    onUninstall = viewModel::uninstall,
                    onRetry = viewModel::refresh,
                )
            }
        }

        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Back to dashboard")
        }
    }
}

/**
 * The install screen's root-module risk warning, now a dismissible alert. It is safety/recovery-
 * relevant, so the permanent "Don't remind" is honored but scoped to the current install state: a
 * MATERIAL change (module installed <-> not installed) surfaces the warning once more, matching the
 * Dashboard's install-risk card. The plain "Dismiss" is reconciled against the same state key so it
 * likewise returns when the install state changes.
 */
@Composable
internal fun RiskCard(moduleInstalled: Boolean) {
    val store = rememberDismissedAlertsStore()
    val stateKey = if (moduleInstalled) "installed" else "not_installed"
    val key = "$INSTALL_RISK_KEY_PREFIX$stateKey"
    LaunchedEffect(key) {
        store.reconcileActive(setOf(key), INSTALL_RISK_KEY_PREFIX)
    }
    PersistentDismissibleAlert(
        alertKey = key,
        permanentAlertKey = key,
        store = store,
        title = "Flashing a root module is risky",
        message = "This installs a Magisk/Zygisk module that hooks the audio capture path. " +
            "It requires root with Magisk and Zygisk enabled, will not work on many " +
            "phones, and can cause boot issues. Only continue if you can recover the device.",
        severity = AlertSeverity.ERROR,
    )
}

@Composable
private fun DetectionCard(state: InstallUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Device status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            StatusRow("Control service", state.serviceConnected, if (state.serviceConnected) "Connected" else "Not connected")
            StatusRow("Magisk + Zygisk", state.magiskDetected, if (state.magiskDetected) "Detected" else "Not detected")
            StatusRow("Zygisk", state.zygiskEnabled, if (state.zygiskEnabled) "Enabled" else "Disabled / unknown")
            StatusRow("Echidna module", state.moduleInstalled, if (state.moduleInstalled) "Installed" else "Not installed")
            Text(
                text = "Engine: ${state.engineSummary}  ·  SELinux ${state.selinuxMode}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, value: String) {
    val accent = if (ok) GreenAccent else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp)
        )
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(140.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = accent
        )
    }
}

@Composable
private fun BusyCard(state: InstallUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    text = state.message ?: "Working…",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MessageCard(phase: InstallPhase, message: String) {
    val container = if (phase == InstallPhase.FAILED) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (phase == InstallPhase.FAILED) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun RebootCard(
    removing: Boolean,
    onReboot: () -> Unit,
    onRecheck: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AmberAccent.copy(alpha = 0.15f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    imageVector = Icons.Filled.RestartAlt,
                    contentDescription = null,
                    tint = AmberAccent,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Reboot required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = if (removing) {
                    "The engine is disabled and removal is scheduled, but a live Zygisk module stays " +
                        "loaded in running processes until the device restarts. Reboot to finish " +
                        "unloading and removing it, then reopen Echidna to confirm it's gone."
                } else {
                    "The module is installed, but a Zygisk module only loads at boot — it can't be " +
                        "hot-swapped into running processes. Reboot to activate the engine, then " +
                        "reopen Echidna to confirm it's active."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onReboot, modifier = Modifier.fillMaxWidth()) {
                Icon(imageVector = Icons.Filled.RestartAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reboot now")
            }
            OutlinedButton(onClick = onRecheck, modifier = Modifier.fillMaxWidth()) {
                Text("Re-check status")
            }
        }
    }
}

@Composable
private fun ActionsCard(
    state: InstallUiState,
    onInstall: () -> Unit,
    onPickZip: () -> Unit,
    onUninstall: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (state.canInstall) {
                PrimaryAction(
                    label = "Install engine module",
                    icon = Icons.Filled.Download,
                    enabled = true,
                    onClick = onInstall
                )
                OutlinedButton(
                    onClick = onPickZip,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Filled.FolderZip, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select module .zip instead")
                }
            }

            if (state.canUninstall) {
                Button(
                    onClick = onUninstall,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(imageVector = Icons.Filled.Cancel, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Uninstall engine module")
                }
            }

            if (!state.canInstall && !state.canUninstall) {
                if (!state.serviceConnected || state.phase == InstallPhase.FAILED) {
                    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text("Retry")
                    }
                } else {
                    Text(
                        text = "No install action is available on this device. See the device status above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun OpenMagiskButton() {
    val context = LocalContext.current
    var showFallback by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = {
                val intent = MagiskLauncher.resolveLaunchIntent(context.packageManager)
                showFallback = if (intent != null) {
                    // Launch succeeded → clear any prior fallback notice; if the launch itself
                    // throws (rare), fall back to guidance rather than crashing.
                    !runCatching { context.startActivity(intent) }.isSuccess
                } else {
                    true
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open Magisk")
        }
        if (showFallback) {
            Text(
                text = "Couldn't open Magisk automatically. If Magisk is hidden or repackaged, " +
                    "open it manually from your app drawer to enable Zygisk or manage modules.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PrimaryAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Icon(imageVector = icon, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

private const val ZIP_MIME = "application/zip"
