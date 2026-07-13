package com.echidna.app.ui.diagnostics

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echidna.app.model.HookTelemetry
import com.echidna.app.model.ModuleStatus
import com.echidna.app.model.TelemetrySnapshot
import java.util.Locale

/**
 * A vertical visualization of the Echidna capture pipeline:
 *
 *   Capture source → [hook layer] → DSP (echidna_process_block · libech_dsp.so)
 *   → Processed PCM → App / System consumer
 *
 * The seven interception hooks are shown in the exact priority order the native
 * orchestrator attempts them (audio_hook_orchestrator.cpp installHooks():
 * AAudio → OpenSL ES → AudioFlinger → AudioRecord → libc read → tinyALSA → HAL).
 * The orchestrator installs the FIRST that succeeds and stops, so only ONE hook is
 * the live capture path — that one is highlighted from real telemetry (the hook whose
 * reported `successes > 0`), the rest are dimmed with their honest per-hook status.
 *
 * All motion is gated on REAL data. The flow only animates (moving dots along the
 * connectors + a pulsing DSP node) when the engine is genuinely hooking AND processing
 * audio — an installed hook plus a non-zero processed-block counter
 * ([TelemetrySnapshot.totalCallbacks], the count of echidna_process_block invocations).
 * When the engine is idle / not hooking (e.g. an unrooted device) the pipeline is drawn
 * static and greyed with an explicit "not active" note — never faked motion.
 */
@Composable
fun AudioPipelineView(
    telemetry: TelemetrySnapshot,
    moduleStatus: ModuleStatus?,
    bypass: Boolean
) {
    // Map each reported hook to its canonical pipeline slot (orchestrator order).
    val byIndex = HashMap<Int, HookTelemetry>()
    telemetry.hooks.forEach { hook ->
        val idx = classifyHook(hook.name)
        if (idx >= 0) {
            // If two entries map to one slot, keep the one that actually installed.
            val existing = byIndex[idx]
            if (existing == null || hook.successes > existing.successes) byIndex[idx] = hook
        }
    }
    // The active hook = highest-priority slot the engine actually installed.
    val activeIndex = PIPELINE_HOOKS.indices.firstOrNull { (byIndex[it]?.successes ?: 0) > 0 }
    val activeHook = activeIndex?.let { byIndex[it] }

    // echidna_process_block is running when the processed-block counter is non-zero.
    val processing = telemetry.totalCallbacks > 0L
    val hooking = activeIndex != null
    // Only animate the live flow when a hook is installed AND blocks are being processed.
    val animate = hooking && processing

    val accent = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.outlineVariant
    val flowColor = if (animate) accent else idleColor

    val sampleRate = moduleStatus?.audioStack?.sampleRate?.takeIf { it > 0 }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("Audio pipeline", style = MaterialTheme.typography.titleMedium)
            Text(
                text = when {
                    animate -> "Live — capturing and processing audio"
                    hooking -> "Hook installed — awaiting audio blocks"
                    else -> "Not active — no live capture on this device"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (animate) accent else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Box(modifier = Modifier.height(4.dp))

            // 1 — Capture source
            PipelineNode(
                title = "Capture source",
                subtitle = "Microphone / audio input",
                metric = sampleRate?.let { "$it Hz" },
                active = animate
            )
            FlowConnector(animate = animate, color = flowColor)

            // 2 — Hook layer (the 7 interception points, active one highlighted)
            HookLayerNode(
                byIndex = byIndex,
                activeIndex = activeIndex,
                hooking = hooking,
                activeHook = activeHook
            )
            FlowConnector(animate = animate, color = flowColor)

            // 3 — DSP
            PipelineNode(
                title = "DSP · echidna_process_block",
                subtitle = if (bypass) "libech_dsp.so — bypassed (passthrough)"
                else "libech_dsp.so",
                metric = if (processing) {
                    "${telemetry.totalCallbacks} blocks"
                } else null,
                active = animate && !bypass,
                pulse = animate && !bypass
            )
            FlowConnector(animate = animate, color = flowColor)

            // 4 — Processed PCM
            PipelineNode(
                title = "Processed PCM",
                subtitle = "Transformed audio buffer",
                metric = null,
                active = animate
            )
            FlowConnector(animate = animate, color = flowColor)

            // 5 — Consumer
            PipelineNode(
                title = "App / System consumer",
                subtitle = "Delivered to the capturing app",
                metric = null,
                active = animate
            )

            if (!hooking) {
                Box(modifier = Modifier.height(4.dp))
                Text(
                    text = "The engine is not hooking audio on this device, so no signal " +
                        "flows through the pipeline. On a rooted device with the Zygisk module " +
                        "active, the winning capture hook is highlighted and audio animates " +
                        "along the path in real time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Nodes
// ---------------------------------------------------------------------------

@Composable
private fun PipelineNode(
    title: String,
    subtitle: String?,
    metric: String?,
    active: Boolean,
    pulse: Boolean = false
) {
    val accent = MaterialTheme.colorScheme.primary
    // A pulsing border only exists while `pulse` is true; when idle no animation runs.
    val borderAlpha = if (pulse) {
        val transition = rememberInfiniteTransition(label = "node-pulse")
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "node-pulse-alpha"
        ).value
    } else {
        1f
    }
    val borderColor = if (active) accent.copy(alpha = borderAlpha) else MaterialTheme.colorScheme.outlineVariant
    val container = if (active) accent.copy(alpha = 0.10f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = container,
        border = BorderStroke(if (active) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusDot(active = active)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            metric?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HookLayerNode(
    byIndex: Map<Int, HookTelemetry>,
    activeIndex: Int?,
    hooking: Boolean,
    activeHook: HookTelemetry?
) {
    val accent = MaterialTheme.colorScheme.primary
    val borderColor = if (hooking) accent else MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(if (hooking) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Capture hook layer",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                activeHook?.let {
                    Text(
                        text = "ok ${it.successes} · failed ${it.failures}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = accent
                    )
                }
            }
            Text(
                text = "Orchestrator installs the first that succeeds, in priority order.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PIPELINE_HOOKS.forEachIndexed { index, hook ->
                HookRow(
                    label = hook.label,
                    isActive = index == activeIndex,
                    statusText = hookStatusText(index, activeIndex, byIndex[index])
                )
            }
        }
    }
}

@Composable
private fun HookRow(label: String, isActive: Boolean, statusText: String) {
    val accent = MaterialTheme.colorScheme.primary
    val bg = if (isActive) accent.copy(alpha = 0.14f) else Color.Transparent
    val fg = if (isActive) accent else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusDot(active = isActive)
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = fg,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = fg
            )
        }
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Canvas(modifier = Modifier.size(8.dp)) {
        drawCircle(color = color)
    }
}

// ---------------------------------------------------------------------------
// Connector
// ---------------------------------------------------------------------------

@Composable
private fun FlowConnector(animate: Boolean, color: Color) {
    // The infinite transition is created only while animating, so the idle pipeline
    // does no per-frame work.
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "flow")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "flow-phase"
        ).value
    } else {
        0f
    }
    val dotColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
    ) {
        val cx = size.width / 2f
        val stroke = 2.dp.toPx()
        drawLine(
            color = color,
            start = Offset(cx, 0f),
            end = Offset(cx, size.height),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        // Directional arrowhead at the bottom.
        val head = 4.dp.toPx()
        drawLine(color, Offset(cx - head, size.height - head), Offset(cx, size.height), stroke, StrokeCap.Round)
        drawLine(color, Offset(cx + head, size.height - head), Offset(cx, size.height), stroke, StrokeCap.Round)
        if (animate) {
            val dots = 3
            for (i in 0 until dots) {
                val f = (phase + i.toFloat() / dots) % 1f
                drawCircle(
                    color = dotColor,
                    radius = 3.dp.toPx(),
                    center = Offset(cx, f * size.height)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Hook classification (mirrors the orchestrator's priority order)
// ---------------------------------------------------------------------------

private data class PipelineHook(val label: String, val description: String)

/**
 * The seven capture hooks in the exact order audio_hook_orchestrator.cpp attempts
 * them. Index into this list is the canonical "slot" for a reported hook.
 */
private val PIPELINE_HOOKS = listOf(
    PipelineHook("AAudio", "Native low-latency capture API (Android 8+)."),
    PipelineHook("OpenSL ES", "Legacy native audio API."),
    PipelineHook("AudioFlinger", "System audio mixer / record-server path."),
    PipelineHook("AudioRecord", "Java / NDK microphone capture client."),
    PipelineHook("libc read()", "Raw file-descriptor read interception."),
    PipelineHook("tinyALSA", "Low-level ALSA PCM device access."),
    PipelineHook("Audio HAL", "Vendor hardware-abstraction-layer capture.")
)

/** Classifies a reported hook name into its [PIPELINE_HOOKS] slot, or -1 if unknown. */
private fun classifyHook(name: String): Int {
    val key = name.lowercase(Locale.US)
    return when {
        key.contains("aaudio") -> 0
        key.contains("opensl") || key.contains("sles") -> 1
        key.contains("audioflinger") -> 2
        key.contains("audiorecord") -> 3
        key.contains("tinyalsa") || key.contains("pcm") -> 5
        key.contains("hal") -> 6
        key.contains("read") -> 4
        else -> -1
    }
}

/** Honest per-hook status: only the installed one is active; the rest reflect real telemetry. */
private fun hookStatusText(index: Int, activeIndex: Int?, hook: HookTelemetry?): String = when {
    index == activeIndex -> "active"
    hook != null && hook.successes > 0 -> "installed"
    hook != null && hook.attempts > 0 -> "failed"
    hook != null -> "pending"
    activeIndex != null && index > activeIndex -> "not reached"
    else -> "—"
}
