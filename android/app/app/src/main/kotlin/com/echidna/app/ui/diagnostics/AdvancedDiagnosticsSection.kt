package com.echidna.app.ui.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echidna.app.model.CaptureOwner
import com.echidna.app.model.CaptureOwnerReason
import com.echidna.app.model.CaptureOwnerStatus
import com.echidna.app.model.DspMetrics
import com.echidna.app.model.HookTelemetry
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.ModuleStatus
import com.echidna.app.model.TelemetrySnapshot
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Advanced diagnostics: an expandable section that surfaces the deeper, REAL engine
 * detail that the standard panels omit — per audio-API hook status, audio-pipeline
 * counters, performance timing, the de-faked SELinux/HAL/Magisk environment probe, and
 * a raw status dump. Every value comes from a real flow ([TelemetrySnapshot] parsed from
 * authenticated native socket telemetry, or [ModuleStatus] from the control service's
 * getModuleStatus()). When the engine is not active — e.g. an unrooted device — the
 * telemetry-backed groups honestly show "unavailable"/"engine not active" rather than
 * fabricated zeros, and the environment probe still reports the real device state.
 */
@Composable
fun AdvancedDiagnosticsSection(
    moduleStatus: ModuleStatus?,
    telemetry: TelemetrySnapshot,
    metrics: DspMetrics,
    latencyMode: LatencyMode,
    masterEnabled: Boolean,
    bypass: Boolean,
    captureOwnerStatus: CaptureOwnerStatus
) {
    var expanded by remember { mutableStateOf(false) }
    // Legacy shared-memory counters are explicitly unverified and cannot make the runtime live.
    val telemetryLive = telemetry.hasVerifiedRuntimeTelemetry

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Advanced diagnostics", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Hook status, audio-pipeline, performance & environment detail",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Hide advanced diagnostics" else "Show advanced diagnostics"
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (!telemetryLive) {
                        Text(
                            text = "Engine not active. Live pipeline and performance metrics are " +
                                "unavailable on this device — they populate only while the Zygisk " +
                                "engine is running and hooking audio. The environment probe below " +
                                "still reflects this device's real state.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    CaptureOwnershipGroup(captureOwnerStatus)
                    HorizontalDivider()
                    HookStatusGroup(telemetry.hooks, telemetryLive)
                    HorizontalDivider()
                    HookAttachGroup(telemetry, telemetryLive)
                    HorizontalDivider()
                    AudioPipelineGroup(moduleStatus, telemetry, latencyMode, telemetryLive)
                    HorizontalDivider()
                    PerformanceGroup(metrics, telemetry, telemetryLive)
                    HorizontalDivider()
                    EnvironmentGroup(moduleStatus)
                    HorizontalDivider()
                    RawStatusGroup(moduleStatus, telemetry, masterEnabled, bypass)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Groups
// ---------------------------------------------------------------------------

/**
 * Who owns audio capture for the whitelisted apps, and why nobody does when nobody does. Neither
 * engine can report this from the target process, and the LSPosed shim is silently inert whenever
 * it is not the named owner — so this is the only place the user can find that out.
 */
@Composable
private fun CaptureOwnershipGroup(status: CaptureOwnerStatus) {
    GroupHeader(
        title = "Capture ownership",
        description = "Exactly one engine may transform a whitelisted app's audio. The LSPosed " +
            "shim does nothing unless it is the owner."
    )
    val owned = status.reason == CaptureOwnerReason.ACTIVE
    MetricRow(
        label = "Effective capture owner",
        value = if (owned) status.owner.label else null,
        description = if (owned) {
            "Owner published to every enabled whitelist entry."
        } else {
            status.reason.summary
        }
    )
    if (owned && status.owner == CaptureOwner.ZYGISK) {
        NoteLine(
            "The LSPosed shim is inert while Zygisk owns capture. To hand capture to the shim, " +
                "set Settings -> Engine -> DSP engine mode -> Compatibility."
        )
    }
    if (owned && status.owner == CaptureOwner.LSPOSED) {
        NoteLine(
            "The shim hooks android.media.AudioRecord only. Apps that capture through " +
                "AAudio, OpenSL ES, or Oboe are not covered by it in any configuration."
        )
    }
}

@Composable
private fun HookStatusGroup(hooks: List<HookTelemetry>, telemetryLive: Boolean) {
    GroupHeader(
        title = "Hook status",
        description = "Per audio-API interception hook reported by the engine."
    )
    if (hooks.isEmpty()) {
        UnavailableLine(
            if (telemetryLive) "No hook attempts reported." else "Engine not active — no hook data."
        )
        return
    }
    hooks.forEach { HookAdvancedRow(it) }
}

@Composable
private fun HookAdvancedRow(hook: HookTelemetry) {
    val meta = hookMeta(hook.name)
    val status = when {
        hook.successes > 0 && hook.failures == 0 -> "Installed"
        hook.successes > 0 -> "Installed (with failures)"
        hook.attempts > 0 -> "Not installed"
        else -> "Pending"
    }
    val installed = hook.successes > 0
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                meta.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            InfoTooltip(meta.description)
            StatusPill(text = status, positive = installed)
        }
        Text(
            text = "attempts ${hook.attempts} • ok ${hook.successes} • failed ${hook.failures}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val detail = buildList {
            if (hook.library.isNotBlank()) add("lib=${hook.library}")
            if (hook.symbol.isNotBlank()) add("sym=${hook.symbol}")
            if (hook.reason.isNotBlank()) add("reason=${hook.reason}")
        }.joinToString(" • ")
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HookAttachGroup(telemetry: TelemetrySnapshot, telemetryLive: Boolean) {
    GroupHeader(
        title = "Hook attach",
        description = "Install/attach level and events, kept separate from block processing " +
            "so an attach failure is never mistaken for a block-processing failure."
    )
    MetricRow(
        label = "Any route attached",
        value = if (telemetryLive) if (telemetry.anyRouteInstalled) "yes" else "no" else null,
        description = "Whether at least one audio route currently reports an installed hook."
    )
    MetricRow(
        label = "Install / attach events",
        value = if (telemetryLive) telemetry.totalInstallEvents.toString() else null,
        description = "Hook attach level transitions observed — not audio block counts."
    )
    MetricRow(
        label = "Install / attach failures",
        value = if (telemetryLive) telemetry.totalInstallFailures.toString() else null,
        description = "Attach attempts that failed, counted apart from block-processing failures."
    )
}

@Composable
private fun AudioPipelineGroup(
    moduleStatus: ModuleStatus?,
    telemetry: TelemetrySnapshot,
    latencyMode: LatencyMode,
    telemetryLive: Boolean
) {
    GroupHeader(
        title = "Audio pipeline",
        description = "Block flow through the hooked audio path."
    )
    val stack = moduleStatus?.audioStack
    MetricRow(
        label = "Audio callbacks processed",
        value = if (telemetryLive) telemetry.totalCallbacks.toString() else null,
        description = "Total audio blocks the engine has processed since start."
    )
    MetricRow(
        label = "Bypassed blocks",
        value = if (telemetryLive) telemetry.totalBypasses.toString() else null,
        description = "Blocks admitted but intentionally left untransformed (bypass / policy)."
    )
    MetricRow(
        label = "XRuns / underruns",
        value = if (telemetryLive) telemetry.xruns.toString() else null,
        description = "Dropped or late audio buffers detected in the callback."
    )
    MetricRow(
        label = "Sample rate",
        value = stack?.sampleRate?.takeIf { it > 0 }?.let { "$it Hz" },
        description = "Device output sample rate (AudioManager)."
    )
    MetricRow(
        label = "Block size (frames)",
        value = stack?.framesPerBuffer?.takeIf { it > 0 }?.toString(),
        description = "Native audio block size the low-latency path uses."
    )
    MetricRow(
        label = "Latency mode (target)",
        value = "${latencyMode.label} (~${latencyMode.targetMs} ms)",
        description = "Configured processing-latency target, not a live measurement."
    )
    NoteLine("Ring/queue depth is not exported by the engine, so it is not shown.")
}

@Composable
private fun PerformanceGroup(
    metrics: DspMetrics,
    telemetry: TelemetrySnapshot,
    telemetryLive: Boolean
) {
    GroupHeader(
        title = "Performance",
        description = "Processing time and CPU cost of the DSP callback."
    )
    MetricRow(
        label = "Average CPU load",
        value = if (telemetryLive) String.format(Locale.US, "%.1f%%", metrics.cpuLoadPercent) else null,
        description = "Mean fraction of the callback deadline spent in DSP."
    )
    MetricRow(
        label = "Average processing latency",
        value = if (telemetryLive) String.format(Locale.US, "%.2f ms", metrics.endToEndLatencyMs) else null,
        description = "Mean wall-clock time to process one audio block."
    )
    val last = telemetry.samples.lastOrNull()
    MetricRow(
        label = "Last callback",
        value = last?.let { "${it.durationUs} µs wall / ${it.cpuUs} µs CPU" },
        description = "Timing of the most recent processed block."
    )
    MetricRow(
        label = "Samples buffered",
        value = if (telemetryLive) telemetry.samples.size.toString() else null,
        description = "Per-callback records currently in the telemetry ring."
    )
    NoteLine("Per-effect CPU breakdown and hybrid-worker stats are not exported by the engine.")
}

@Composable
private fun EnvironmentGroup(moduleStatus: ModuleStatus?) {
    GroupHeader(
        title = "Environment",
        description = "Real Magisk / Zygisk / SELinux / audio-HAL probe from the control service."
    )
    if (moduleStatus == null) {
        UnavailableLine("Control service not bound — environment probe unavailable.")
        return
    }
    val stack = moduleStatus.audioStack
    val cpu = moduleStatus.cpu
    MetricRow(
        label = "SELinux",
        value = "${moduleStatus.selinuxStatus} (${moduleStatus.selinuxState})",
        description = "Kernel enforcement state and Echidna policy posture."
    )
    MetricRow(
        label = "CPU architecture",
        value = "${cpu.cpuFamily} (${cpu.primaryAbi.ifBlank { "unknown ABI" }})",
        description = "Primary process ABI reported by Android."
    )
    MetricRow(
        label = "Zygisk ABI",
        value = cpu.zygiskAbi.ifBlank { "unknown" },
        description = "Per-process payload name Magisk/Zygisk should select."
    )
    MetricRow(
        label = "Native hook ABI support",
        value = if (cpu.nativeHooksSupported) "supported" else "limited",
        description = cpu.message.ifBlank { "CPU/ABI compatibility probe unavailable." }
    )
    if (cpu.supportedAbis.isNotEmpty()) {
        MetricRow(
            label = "Supported ABIs",
            value = cpu.supportedAbis.joinToString(),
            description = "ABI order reported by Build.SUPPORTED_ABIS."
        )
    }
    MetricRow(
        label = "Magisk module",
        value = if (moduleStatus.magiskModuleInstalled) "installed" else "not installed",
        description = "Whether the Echidna Magisk module is present."
    )
    MetricRow(
        label = "Zygisk",
        value = if (moduleStatus.zygiskEnabled) "enabled" else "disabled",
        description = "Zygote injection required for in-process audio hooking."
    )
    MetricRow(
        label = "Java fallback",
        value = if (moduleStatus.javaFallbackRecommended) "recommended" else "not recommended",
        description = "Recommendation only; this does not prove an LSPosed route is active."
    )
    MetricRow(
        label = "Native capture route",
        value = if (moduleStatus.nativeRouteVerified) "verified" else "unverified",
        description = "Requires recent transformed-buffer runtime proof."
    )
    MetricRow(
        label = "Audio HAL",
        value = stack.hal.ifBlank { null },
        description = "Vendor hardware-abstraction-layer / audio board."
    )
    MetricRow(
        label = "Vendor family",
        value = stack.vendorFamily.ifBlank { null },
        description = "Best-effort SoC/HAL family classification from build properties."
    )
    MetricRow(
        label = "AAudio (low-latency)",
        value = if (stack.aaudioSupported) "supported" else "not reported",
        description = "Native low-latency capture/playback API availability."
    )
    MetricRow(
        label = "OpenSL ES library",
        value = if (stack.openSlEsAvailable) "present" else "not found",
        description = "Whether libOpenSLES.so exists in common system/vendor library paths."
    )
    MetricRow(
        label = "AudioFlinger client lib",
        value = if (stack.audioFlingerClientAvailable) "present" else "not found",
        description = "Whether libaudioclient.so exists for client-path hook probing."
    )
    MetricRow(
        label = "tinyalsa library",
        value = if (stack.tinyAlsaAvailable) "present" else "not found",
        description = "Whether libtinyalsa.so exists for lower-level PCM hook probing."
    )
    MetricRow(
        label = "Low-latency feature",
        value = if (stack.lowLatency) "present" else "absent",
        description = "FEATURE_AUDIO_LOW_LATENCY reported by the device."
    )
    MetricRow(
        label = "Pro audio",
        value = if (stack.proAudio) "present" else "absent",
        description = "FEATURE_AUDIO_PRO reported by the device."
    )
    moduleStatus.notes?.let { NoteLine(it) }
    moduleStatus.lastError?.let {
        Text(
            text = "Last error: $it",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun RawStatusGroup(
    moduleStatus: ModuleStatus?,
    telemetry: TelemetrySnapshot,
    masterEnabled: Boolean,
    bypass: Boolean
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = !open },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Raw status", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "JSON rebuilt from the parsed module status + telemetry snapshot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (open) "Hide raw status" else "Show raw status"
        )
    }
    AnimatedVisibility(visible = open) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = buildRawStatusJson(moduleStatus, telemetry, masterEnabled, bypass),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------

@Composable
private fun GroupHeader(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A labelled metric. A null [value] renders as an honest "unavailable" dash rather than a
 * fabricated zero, so the reader can tell live numbers from missing ones.
 */
@Composable
private fun MetricRow(label: String, value: String?, description: String) {
    Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = value ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (value == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
        Text(
            text = if (value == null) "$description  (unavailable)" else description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UnavailableLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun NoteLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun StatusPill(text: String, positive: Boolean) {
    val bg = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (positive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = bg, shape = MaterialTheme.shapes.small) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoTooltip(text: String) {
    val tooltipState = rememberTooltipState()
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text) } },
        state = tooltipState
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = "About this hook",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(14.dp)
                .clickable { scope.launch { tooltipState.show() } }
        )
    }
}

private data class HookMeta(val label: String, val description: String)

/** Maps a reported hook name to a friendly label + description. Only hooks that actually
 *  report telemetry are ever shown, so no interception point is implied that did not run. */
private fun hookMeta(name: String): HookMeta {
    val key = name.lowercase(Locale.US)
    return when {
        key.contains("aaudio") ->
            HookMeta("AAudio", "Native low-latency capture/playback API (Android 8+).")
        key.contains("opensl") || key.contains("sles") ->
            HookMeta("OpenSL ES", "Legacy native audio API.")
        key.contains("audioflinger") ->
            HookMeta("AudioFlinger", "System audio mixer / record-server path.")
        key.contains("audiorecord") ->
            HookMeta("AudioRecord", "Java / NDK microphone capture client.")
        key.contains("tinyalsa") || key.contains("pcm") ->
            HookMeta("tinyALSA", "Low-level ALSA PCM device access.")
        key.contains("hal") ->
            HookMeta("Audio HAL", "Vendor hardware-abstraction-layer capture.")
        key.contains("read") ->
            HookMeta("libc read()", "Raw file-descriptor read interception.")
        else -> HookMeta(name.ifBlank { "Unknown" }, "Audio interception hook.")
    }
}

private fun buildRawStatusJson(
    moduleStatus: ModuleStatus?,
    telemetry: TelemetrySnapshot,
    masterEnabled: Boolean,
    bypass: Boolean
): String {
    val root = JSONObject()
    root.put("masterEnabled", masterEnabled)
    root.put("bypass", bypass)
    if (moduleStatus == null) {
        root.put("moduleStatus", JSONObject.NULL)
    } else {
        val stack = moduleStatus.audioStack
        root.put(
            "moduleStatus",
            JSONObject()
                .put("magiskModuleInstalled", moduleStatus.magiskModuleInstalled)
                .put("zygiskEnabled", moduleStatus.zygiskEnabled)
                .put("selinuxState", moduleStatus.selinuxState)
                .put("selinuxStatus", moduleStatus.selinuxStatus)
                .put("policyToolAvailable", moduleStatus.policyToolAvailable)
                .put("policyAppliedVerified", moduleStatus.policyAppliedVerified)
                .put("nativeRouteVerified", moduleStatus.nativeRouteVerified)
                .put("javaFallbackRecommended", moduleStatus.javaFallbackRecommended)
                .put(
                    "cpu",
                    JSONObject()
                        .put("primaryAbi", moduleStatus.cpu.primaryAbi)
                        .put("supportedAbis", JSONArray(moduleStatus.cpu.supportedAbis))
                        .put("cpuFamily", moduleStatus.cpu.cpuFamily)
                        .put("is64Bit", moduleStatus.cpu.is64Bit)
                        .put("zygiskAbi", moduleStatus.cpu.zygiskAbi)
                        .put("moduleSupported", moduleStatus.cpu.moduleSupported)
                        .put("nativeHooksSupported", moduleStatus.cpu.nativeHooksSupported)
                        .put("supportLevel", moduleStatus.cpu.supportLevel)
                        .put("message", moduleStatus.cpu.message)
                )
                .put("notes", moduleStatus.notes ?: JSONObject.NULL)
                .put("lastError", moduleStatus.lastError ?: JSONObject.NULL)
                .put(
                    "audioStack",
                    JSONObject()
                        .put("hal", stack.hal)
                        .put("manufacturer", stack.manufacturer)
                        .put("boardPlatform", stack.boardPlatform)
                        .put("vendorFamily", stack.vendorFamily)
                        .put("aaudioSupported", stack.aaudioSupported)
                        .put("openSlEsAvailable", stack.openSlEsAvailable)
                        .put("audioFlingerClientAvailable", stack.audioFlingerClientAvailable)
                        .put("tinyAlsaAvailable", stack.tinyAlsaAvailable)
                        .put("lowLatency", stack.lowLatency)
                        .put("proAudio", stack.proAudio)
                        .put("sampleRate", stack.sampleRate)
                        .put("framesPerBuffer", stack.framesPerBuffer)
                )
        )
    }
    root.put(
        "telemetry",
        JSONObject()
            .put("totalCallbacks", telemetry.totalCallbacks)
            .put("averageLatencyMs", telemetry.averageLatencyMs.toDouble())
            .put("averageCpuPercent", telemetry.averageCpuPercent.toDouble())
            .put("inputRms", telemetry.inputRms.toDouble())
            .put("outputRms", telemetry.outputRms.toDouble())
            .put("inputPeak", telemetry.inputPeak.toDouble())
            .put("outputPeak", telemetry.outputPeak.toDouble())
            .put("detectedPitchHz", telemetry.detectedPitchHz.toDouble())
            .put("targetPitchHz", telemetry.targetPitchHz.toDouble())
            .put("formantShiftCents", telemetry.formantShiftCents.toDouble())
            .put("formantWidth", telemetry.formantWidth.toDouble())
            .put("xruns", telemetry.xruns)
            .put("sampleCount", telemetry.samples.size)
            .put("warnings", JSONArray(telemetry.warnings))
            .put(
                "hooks",
                JSONArray().apply {
                    telemetry.hooks.forEach { hook ->
                        put(
                            JSONObject()
                                .put("name", hook.name)
                                .put("library", hook.library)
                                .put("symbol", hook.symbol)
                                .put("reason", hook.reason)
                                .put("attempts", hook.attempts)
                                .put("successes", hook.successes)
                                .put("failures", hook.failures)
                        )
                    }
                }
            )
    )
    return runCatching { root.toString(2) }.getOrDefault(root.toString())
}
