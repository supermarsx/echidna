package com.echidna.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echidna.app.data.DismissedAlertsStore

/** Severity of an in-app alert, mapped to the app's Material3 colour roles. */
enum class AlertSeverity { INFO, WARNING, ERROR }

/**
 * A reusable, dismissible in-app alert/banner styled to the app's Material3 theme.
 *
 * Renders a coloured [Card] carrying an optional [title], a [message], a severity icon, and an
 * action row. The row always ends with a **Dismiss** affordance; when *both* [actionLabel] and
 * [onAction] are provided it also shows a leading directing-action button, giving the
 * "[Action] [Dismiss]" layout the product asks for (an actionable alert that points the user at
 * the right place). When the action pair is absent the banner is dismiss-only.
 *
 * This composable is **stateless** — it never hides itself. The caller owns visibility (see
 * [PersistentDismissibleAlert] for the persisted, self-hiding variant).
 *
 * When [onDismissPermanent] is provided the row also shows a **"Don't remind"** affordance next to
 * the plain dismiss, giving the user a memorized-forever dismissal distinct from the temporary one.
 *
 * @param actionLabel label for the optional directing-action button; null for dismiss-only.
 * @param onAction invoked when the action button is tapped; must be non-null with [actionLabel].
 * @param dismissLabel text of the dismiss affordance (defaults to "Dismiss").
 * @param onDismissPermanent invoked for the permanent "don't remind" affordance; null hides it.
 * @param permanentDismissLabel text of the permanent affordance (defaults to "Don't remind").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DismissibleAlert(
    message: String,
    severity: AlertSeverity,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    dismissLabel: String = "Dismiss",
    onDismissPermanent: (() -> Unit)? = null,
    permanentDismissLabel: String = "Don't remind",
) {
    val colors = severity.colors()
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(AlertTestTags.CARD),
        colors = CardDefaults.cardColors(
            containerColor = colors.container,
            contentColor = colors.onContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = severity.icon(),
                contentDescription = severity.contentDescription(),
                tint = colors.accent,
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        // Action row: optional directing action, then the always-present dismiss.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (actionLabel != null && onAction != null) {
                FilledTonalButton(
                    onClick = onAction,
                    modifier = Modifier.testTag(AlertTestTags.ACTION),
                ) {
                    Text(actionLabel)
                }
                Spacer(Modifier.width(8.dp))
            }
            if (onDismissPermanent != null) {
                TextButton(
                    onClick = onDismissPermanent,
                    modifier = Modifier.testTag(AlertTestTags.PERMANENT_DISMISS),
                ) {
                    Text(permanentDismissLabel)
                }
                Spacer(Modifier.width(4.dp))
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(AlertTestTags.DISMISS),
            ) {
                Text(dismissLabel)
            }
        }
    }
}

/**
 * A [DismissibleAlert] that persists its own dismissed state via [store], keyed on the stable
 * [alertKey], and removes itself from the layout once dismissed.
 *
 * The initial visibility is decided synchronously from [store] during composition (keyed on
 * [alertKey] and [permanentAlertKey]) so a previously-dismissed alert never flashes on launch. For
 * condition-driven alerts, callers should build [alertKey] from the condition and call
 * [DismissedAlertsStore.reconcileActive] so the temporary dismissal reappears when the condition
 * recurs.
 *
 * When [allowPermanentDismiss] is true (the default) a "don't remind" affordance is shown; picking
 * it records a *permanent* dismissal under [permanentAlertKey] (defaults to [alertKey]) that
 * `reconcileActive` never clears, so the alert never returns. A caller that wants a safety-relevant
 * alert to re-appear only on a material state change encodes that state into [permanentAlertKey].
 */
@Composable
fun PersistentDismissibleAlert(
    alertKey: String,
    store: DismissedAlertsStore,
    message: String,
    severity: AlertSeverity,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    dismissLabel: String = "Dismiss",
    allowPermanentDismiss: Boolean = true,
    permanentAlertKey: String = alertKey,
    permanentDismissLabel: String = "Don't remind",
) {
    // rememberSaveable keyed on both keys: recomputes (re-reads the store) whenever either changes,
    // so a resolved-then-recurring condition, or a material state change encoded in the permanent
    // key, shows the alert again.
    var dismissed by rememberSaveable(alertKey, permanentAlertKey) {
        mutableStateOf(store.isDismissed(alertKey) || store.isPermanentlyDismissed(permanentAlertKey))
    }
    AnimatedVisibility(
        visible = !dismissed,
        exit = shrinkVertically() + fadeOut(),
    ) {
        DismissibleAlert(
            message = message,
            severity = severity,
            onDismiss = {
                store.setDismissed(alertKey, true)
                dismissed = true
            },
            modifier = modifier,
            title = title,
            actionLabel = actionLabel,
            onAction = onAction,
            dismissLabel = dismissLabel,
            onDismissPermanent = if (allowPermanentDismiss) {
                {
                    store.setPermanentlyDismissed(permanentAlertKey, true)
                    dismissed = true
                }
            } else {
                null
            },
            permanentDismissLabel = permanentDismissLabel,
        )
    }
}

/** Composable helper that builds a [DismissedAlertsStore] from the current context. */
@Composable
fun rememberDismissedAlertsStore(): DismissedAlertsStore {
    val context = LocalContext.current
    return remember(context) { DismissedAlertsStore(context) }
}

/** Test tags for locating the banner and its buttons in UI tests. */
object AlertTestTags {
    const val CARD = "dismissible_alert_card"
    const val ACTION = "dismissible_alert_action"
    const val DISMISS = "dismissible_alert_dismiss"
    const val PERMANENT_DISMISS = "dismissible_alert_permanent_dismiss"
}

private data class SeverityColors(val container: Color, val onContainer: Color, val accent: Color)

@Composable
private fun AlertSeverity.colors(): SeverityColors = when (this) {
    AlertSeverity.INFO -> SeverityColors(
        container = MaterialTheme.colorScheme.secondaryContainer,
        onContainer = MaterialTheme.colorScheme.onSecondaryContainer,
        accent = MaterialTheme.colorScheme.secondary,
    )
    AlertSeverity.WARNING -> SeverityColors(
        container = MaterialTheme.colorScheme.tertiaryContainer,
        onContainer = MaterialTheme.colorScheme.onTertiaryContainer,
        accent = MaterialTheme.colorScheme.tertiary,
    )
    AlertSeverity.ERROR -> SeverityColors(
        container = MaterialTheme.colorScheme.errorContainer,
        onContainer = MaterialTheme.colorScheme.onErrorContainer,
        accent = MaterialTheme.colorScheme.error,
    )
}

private fun AlertSeverity.icon(): ImageVector = when (this) {
    AlertSeverity.INFO -> Icons.Filled.Info
    AlertSeverity.WARNING -> Icons.Filled.WarningAmber
    AlertSeverity.ERROR -> Icons.Filled.ErrorOutline
}

private fun AlertSeverity.contentDescription(): String = when (this) {
    AlertSeverity.INFO -> "Information"
    AlertSeverity.WARNING -> "Warning"
    AlertSeverity.ERROR -> "Error"
}
