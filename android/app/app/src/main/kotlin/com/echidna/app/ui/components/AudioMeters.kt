package com.echidna.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echidna.app.model.DspMetrics
import kotlin.math.roundToInt

// Level meters read out dBFS over this window; -60 dBFS is treated as the visual floor.
private const val MIN_DB = -60f
// Latency meter scales against this full-scale target (ms).
private const val LATENCY_FULL_MS = 40f

private val GreenLevel = Color(0xFF4CAF50)
private val AmberLevel = Color(0xFFFFB300)
private val RedLevel = Color(0xFFE53935)

/**
 * Animated audio meters driven entirely by REAL telemetry ([DspMetrics], parsed from the
 * native shared-memory ring buffer). Input/output level meters show the actual RMS with a
 * peak-hold tick (dBFS); CPU-load and processing-latency meters show the real per-callback
 * cost. Every bar is smoothed with [animateFloatAsState] — the motion comes from real value
 * changes (telemetry refreshes ~every 2s), never a fabricated animation.
 *
 * When the engine isn't active ([active] = false) the card shows an honest idle state: bars
 * at rest and "—" readouts, because no audio is being processed. No fake motion.
 */
@Composable
fun AudioMetersCard(
    metrics: DspMetrics,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Meters",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                IdlePill(active)
            }

            LevelMeter("Input", metrics.inputRms, metrics.inputPeak, active)
            LevelMeter("Output", metrics.outputRms, metrics.outputPeak, active)

            StatMeter(
                label = "CPU load",
                fraction = (metrics.cpuLoadPercent / 100f),
                valueText = if (active) "${metrics.cpuLoadPercent.roundToInt()}%" else "—",
                accent = accentForFraction(metrics.cpuLoadPercent / 100f),
                active = active
            )
            StatMeter(
                label = "Latency",
                fraction = (metrics.endToEndLatencyMs / LATENCY_FULL_MS),
                valueText = if (active) "${metrics.endToEndLatencyMs.roundToInt()} ms" else "—",
                accent = accentForFraction(metrics.endToEndLatencyMs / LATENCY_FULL_MS),
                active = active
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "XRuns",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                XrunBadge(if (active) metrics.xruns else 0, active)
            }

            if (!active) {
                Text(
                    text = "Idle — meters populate while the engine is processing audio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LevelMeter(label: String, rmsDb: Float, peakDb: Float, active: Boolean) {
    val target = if (active) dbToFraction(rmsDb) else 0f
    val peakTarget = if (active) dbToFraction(peakDb) else 0f
    val fill by animateFloatAsState(target, tween(220), label = "level-$label")
    val peak by animateFloatAsState(peakTarget, tween(220), label = "peak-$label")

    val track = MaterialTheme.colorScheme.surfaceVariant
    val peakColor = MaterialTheme.colorScheme.onSurface

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (active) "${rmsDb.roundToInt()} dBFS  •  pk ${peakDb.roundToInt()}" else "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
        ) {
            val h = size.height
            val r = CornerRadius(h / 2f, h / 2f)
            drawRoundRect(color = track, cornerRadius = r)
            if (active && fill > 0f) {
                // A green→amber→red gradient spanning the FULL width, so colours map to
                // absolute level; the drawn rect is clipped to the current fill fraction.
                val brush = Brush.horizontalGradient(
                    0.0f to GreenLevel,
                    0.7f to AmberLevel,
                    1.0f to RedLevel,
                    startX = 0f,
                    endX = size.width
                )
                drawRoundRect(
                    brush = brush,
                    size = Size(size.width * fill.coerceIn(0f, 1f), h),
                    cornerRadius = r
                )
            }
            if (active && peak > 0f) {
                val px = (size.width * peak.coerceIn(0f, 1f)).coerceIn(1.5f, size.width - 1.5f)
                drawLine(
                    color = peakColor,
                    start = Offset(px, 0f),
                    end = Offset(px, h),
                    strokeWidth = 3f
                )
            }
        }
    }
}

@Composable
private fun StatMeter(
    label: String,
    fraction: Float,
    valueText: String,
    accent: Color,
    active: Boolean
) {
    val target = if (active) fraction.coerceIn(0f, 1f) else 0f
    val fill by animateFloatAsState(target, tween(220), label = "stat-$label")
    val track = MaterialTheme.colorScheme.surfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
        ) {
            val h = size.height
            val r = CornerRadius(h / 2f, h / 2f)
            drawRoundRect(color = track, cornerRadius = r)
            if (active && fill > 0f) {
                drawRoundRect(
                    color = accent,
                    size = Size(size.width * fill, h),
                    cornerRadius = r
                )
            }
        }
    }
}

@Composable
private fun IdlePill(active: Boolean) {
    val (text, accent) = if (active) {
        "LIVE" to GreenLevel
    } else {
        "IDLE" to MaterialTheme.colorScheme.onSurfaceVariant
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

@Composable
private fun XrunBadge(count: Int, active: Boolean) {
    val accent = when {
        !active -> MaterialTheme.colorScheme.onSurfaceVariant
        count == 0 -> GreenLevel
        else -> AmberLevel
    }
    Surface(
        color = accent.copy(alpha = 0.18f),
        contentColor = accent,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = if (active) count.toString() else "—",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
        )
    }
}

private fun dbToFraction(db: Float): Float =
    ((db - MIN_DB) / (0f - MIN_DB)).coerceIn(0f, 1f)

private fun accentForFraction(fraction: Float): Color = when {
    fraction >= 0.85f -> RedLevel
    fraction >= 0.6f -> AmberLevel
    else -> GreenLevel
}
