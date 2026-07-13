package com.echidna.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.echidna.app.model.EngineStatus

/**
 * The five honest engine states the card can show. Which one applies is derived from the
 * real [EngineStatus] plus the live master/bypass control flags — never fabricated. See
 * [engineUiState].
 */
private enum class EngineUiState(
    val label: String,
    val headline: String,
    val icon: ImageVector
) {
    ACTIVE("Active", "Engine active", Icons.Filled.CheckCircle),
    STANDBY("Standby", "Engine on standby", Icons.Filled.PauseCircleOutline),
    BYPASSED("Bypassed", "Engine bypassed", Icons.Filled.Block),
    NOT_INSTALLED("Not installed", "Engine not installed", Icons.Filled.Download),
    ERROR("Error", "Engine error", Icons.Filled.ErrorOutline)
}

// Semantic accent colours chosen to read clearly on the app's dark Material3 scheme.
private val GreenAccent = Color(0xFF4CAF50)
private val AmberAccent = Color(0xFFFFB300)
private val BlueAccent = Color(0xFF42A5F5)

/**
 * A polished Material3 status card summarising the real engine state: a colour-coded icon
 * badge, a state pill, a concise headline, and a subline built from the actual SELinux mode,
 * latency target and xrun count. The accent colour animates between states. Honest by
 * construction — every state maps to a real [EngineStatus]/control combination, so it never
 * claims "Active" when audio isn't being processed.
 *
 * Shared by the Dashboard and Diagnostics screens.
 */
@Composable
fun EngineStatusCard(
    status: EngineStatus,
    masterEnabled: Boolean,
    bypass: Boolean,
    modifier: Modifier = Modifier
) {
    val state = engineUiState(status, masterEnabled, bypass)
    val accent = accentFor(state)
    val animatedAccent by animateColorAsState(
        targetValue = accent,
        animationSpec = tween(durationMillis = 400),
        label = "engineAccent"
    )
    val subline = subline(state, status)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = animatedAccent.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(animatedAccent.copy(alpha = 0.20f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = state,
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                    label = "engineIcon"
                ) { s ->
                    Icon(
                        imageVector = s.icon,
                        contentDescription = null,
                        tint = animatedAccent,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnimatedContent(
                        targetState = state.headline,
                        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                        label = "engineHeadline"
                    ) { headline ->
                        Text(
                            text = headline,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    StatePill(text = state.label, accent = animatedAccent)
                }
                Text(
                    text = subline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                status.lastError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun StatePill(text: String, accent: Color) {
    Surface(
        color = accent.copy(alpha = 0.20f),
        contentColor = accent,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * Maps the real status + control flags to one of the five honest UI states. Precedence:
 * a reported error wins, then a missing native module, then the live active flag. Master-off
 * means the engine is disabled and dormant (Standby); an explicit bypass means the engine is
 * running but passing audio through untouched (Bypassed) — these are distinct states, and
 * master-off is resolved before bypass so a disabled engine never reads as "Bypassed".
 */
private fun engineUiState(
    status: EngineStatus,
    masterEnabled: Boolean,
    bypass: Boolean
): EngineUiState = when {
    status.lastError != null -> EngineUiState.ERROR
    !status.nativeInstalled -> EngineUiState.NOT_INSTALLED
    status.active -> EngineUiState.ACTIVE
    !masterEnabled -> EngineUiState.STANDBY
    bypass -> EngineUiState.BYPASSED
    else -> EngineUiState.STANDBY
}

@Composable
private fun accentFor(state: EngineUiState): Color = when (state) {
    EngineUiState.ACTIVE -> GreenAccent
    EngineUiState.STANDBY -> BlueAccent
    EngineUiState.BYPASSED -> AmberAccent
    EngineUiState.NOT_INSTALLED -> MaterialTheme.colorScheme.onSurfaceVariant
    EngineUiState.ERROR -> MaterialTheme.colorScheme.error
}

private fun subline(state: EngineUiState, status: EngineStatus): String {
    val selinux = "SELinux ${status.selinuxMode}"
    val latency = status.latencyMs?.takeIf { it > 0 }?.let { "$it ms target" }
    val xruns = status.xruns.takeIf { it > 0 }?.let { "$it xruns" }
    return when (state) {
        EngineUiState.ACTIVE ->
            listOfNotNull("Processing audio", latency, selinux, xruns).joinToString(" · ")
        EngineUiState.STANDBY ->
            listOfNotNull("Master off · engine on standby", selinux).joinToString(" · ")
        EngineUiState.BYPASSED ->
            listOfNotNull("Audio passing through untouched", selinux).joinToString(" · ")
        EngineUiState.NOT_INSTALLED ->
            "Install the Magisk module to enable voice processing · $selinux"
        EngineUiState.ERROR ->
            "The engine reported a problem · $selinux"
    }
}
