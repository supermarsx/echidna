package com.echidna.app.ui.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echidna.app.system.MagiskLauncher
import com.echidna.app.ui.components.PersistentDismissibleAlert
import com.echidna.app.ui.components.rememberDismissedAlertsStore

/**
 * Top-level **Alerts** screen. It gathers every global, condition-driven advisory (install, bridge,
 * hook-scope, hardware, probe, and install-mix-up notices) that previously lived in Settings and
 * presents each as a dismissible banner. Every alert offers:
 *  - a plain **Dismiss** (temporary — reconciled against live conditions, so it returns if the
 *    condition clears and later recurs), and
 *  - a **Don't remind** permanent dismissal that is memorized forever and never reappears.
 *
 * Actionable advisories also carry a directing button routed to the destination that resolves them
 * (installer, Magisk, whitelist, or the compatibility wizard). Where no in-app destination exists,
 * the alert shows guidance text only rather than a dead button.
 */
@Composable
fun AlertsScreen(
    viewModel: AlertsViewModel,
    onOpenInstall: () -> Unit,
    onLaunchWhitelist: () -> Unit,
    onLaunchCompatibility: () -> Unit,
) {
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val moduleStatus by viewModel.moduleStatus.collectAsStateWithLifecycle()
    val compatibility by viewModel.compatibility.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val whitelistBindings by viewModel.whitelistBindings.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val alertStore = rememberDismissedAlertsStore()
    var magiskFallback by remember { mutableStateOf(false) }

    val alerts = buildAdvisoryAlerts(
        settings = settings,
        engineStatus = engineStatus,
        moduleStatus = moduleStatus,
        compatibility = compatibility,
        telemetry = telemetry,
        whitelistBindings = whitelistBindings
    )

    // Reconcile temporary dismissals against the live set of active advisory conditions. Permanent
    // "don't remind" dismissals are stored in a separate set and are never touched here.
    val activeKeys = remember(alerts) { alerts.map(::advisoryAlertKey).toSet() }
    LaunchedEffect(activeKeys) {
        alertStore.reconcileActive(activeKeys, ADVISORY_KEY_PREFIX)
    }

    val openMagisk: () -> Unit = {
        val intent = MagiskLauncher.resolveLaunchIntent(context.packageManager)
        magiskFallback = if (intent != null) {
            !runCatching { context.startActivity(intent) }.isSuccess
        } else {
            true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Alerts", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Live advisories about install, bridge, hook-scope, and hardware conditions " +
                    "that can interfere with Echidna. Alerts never block controls. Dismiss one to " +
                    "hide it until the condition recurs, or pick \"Don't remind\" to hide it for good.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (magiskFallback) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "Couldn't open Magisk automatically. If Magisk is hidden or repackaged, " +
                        "open it manually from your app drawer to enable Zygisk or manage modules.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (alerts.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "No active alerts. Everything Echidna can check looks healthy.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            alerts.forEach { alert ->
                val onAction: (() -> Unit)? = when (alert.action) {
                    AlertActionTarget.INSTALLER -> onOpenInstall
                    AlertActionTarget.WHITELIST -> onLaunchWhitelist
                    AlertActionTarget.COMPAT_WIZARD -> onLaunchCompatibility
                    AlertActionTarget.OPEN_MAGISK -> openMagisk
                    AlertActionTarget.NONE -> null
                }
                PersistentDismissibleAlert(
                    alertKey = advisoryAlertKey(alert),
                    store = alertStore,
                    title = alert.title,
                    message = alert.detail,
                    severity = advisorySeverity(alert.category),
                    actionLabel = alert.action.label(),
                    onAction = onAction,
                )
            }
        }
    }
}
