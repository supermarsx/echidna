package com.echidna.app.ui.lab

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echidna.app.audio.AudioAnalysis
import com.echidna.app.audio.TestTone
import kotlin.math.roundToInt

@Composable
fun LabScreen(viewModel: LabViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onPermissionResult(granted) }

    // Reflect the OS permission state on first composition without prompting.
    val alreadyGranted = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    remember(alreadyGranted) {
        if (alreadyGranted && !state.permissionGranted) viewModel.onPermissionResult(true)
        alreadyGranted
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Lab",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        item { HonestFramingCard(state) }
        state.message?.let { message ->
            item { MessageCard(message, onDismiss = viewModel::clearMessage) }
        }
        item {
            MicCheckCard(
                state = state,
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onStartMeter = viewModel::startMeter,
                onStopMeter = viewModel::stopMeter
            )
        }
        item {
            RecordCard(
                state = state,
                onStartRecording = viewModel::startRecording,
                onStopRecording = viewModel::stopRecording,
                onPlayDry = viewModel::playDry,
                onStopPlayback = viewModel::stopPlayback
            )
        }
        item {
            TestToneCard(
                state = state,
                onSelect = viewModel::selectTestTone,
                onGenerate = viewModel::generateTestTone
            )
        }
        item {
            ProcessCard(
                state = state,
                onSelectPreset = viewModel::selectPreset,
                onProcess = viewModel::processWithPreset,
                onPlayDry = viewModel::playDry,
                onPlayWet = viewModel::playWet,
                onStopPlayback = viewModel::stopPlayback
            )
        }
        item {
            RealtimeCard(
                state = state,
                onConfirmHeadphones = viewModel::confirmHeadphones,
                onStart = viewModel::startRealtime,
                onStop = viewModel::stopRealtime
            )
        }
    }
}

@Composable
private fun HonestFramingCard(state: LabUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Local DSP testbench", style = MaterialTheme.typography.titleMedium)
            Text(
                "This tab runs your OWN microphone and generated test tones through the real DSP " +
                    "engine, in this app's process. It lets you hear and see what the effects do. " +
                    "It does NOT intercept or prove interception of any other app's audio.",
                style = MaterialTheme.typography.bodySmall
            )
            val engineText = if (state.engineAvailable) {
                "DSP engine: loaded" + if (state.apiVersion.isNotEmpty()) " (v${state.apiVersion})" else ""
            } else {
                "DSP engine: unavailable (lite build — rebuild with the native engine to hear transforms)"
            }
            Text(
                engineText,
                style = MaterialTheme.typography.labelMedium,
                color = if (state.engineAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun MessageCard(message: String, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

@Composable
private fun MicCheckCard(
    state: LabUiState,
    onRequestPermission: () -> Unit,
    onStartMeter: () -> Unit,
    onStopMeter: () -> Unit
) {
    SectionCard("1 · Mic check", "Grant mic access and watch the live input level to confirm the mic works.") {
        if (!state.permissionGranted) {
            Text("Microphone permission not granted.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onRequestPermission) { Text("Grant microphone access") }
            return@SectionCard
        }
        LevelMeter("Input", state.inputLevelDbfs)
        if (state.micMode == MicMode.METERING) {
            OutlinedButton(onClick = onStopMeter) { Text("Stop meter") }
        } else {
            Button(onClick = onStartMeter, enabled = state.micMode == MicMode.IDLE) { Text("Start input meter") }
        }
    }
}

@Composable
private fun RecordCard(
    state: LabUiState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onPlayDry: () -> Unit,
    onStopPlayback: () -> Unit
) {
    SectionCard("2 · Record → listen back", "Capture a few seconds, see the waveform, and play the dry recording.") {
        if (!state.permissionGranted) {
            Text("Grant microphone access above first.", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        if (state.micMode == MicMode.RECORDING) {
            LevelMeter("Input", state.inputLevelDbfs)
            OutlinedButton(onClick = onStopRecording) { Text("Stop recording") }
        } else {
            Button(onClick = onStartRecording, enabled = state.micMode == MicMode.IDLE) { Text("Record") }
        }
        state.dryWaveform?.let { wave ->
            Text(state.dryLabel, style = MaterialTheme.typography.labelMedium)
            WaveformView(wave.peaks, MaterialTheme.colorScheme.primary)
            LevelSummary("Dry", wave)
            PlaybackRow(
                playingThis = state.playback == PlaybackTarget.DRY,
                onPlay = onPlayDry,
                onStop = onStopPlayback,
                label = "Play dry"
            )
        }
    }
}

@Composable
private fun TestToneCard(
    state: LabUiState,
    onSelect: (TestTone.Kind) -> Unit,
    onGenerate: () -> Unit
) {
    SectionCard("3 · Test tone", "Use a known signal for a deterministic before/after (no mic needed).") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TestTone.Kind.values().forEach { kind ->
                FilterChip(
                    selected = state.testToneKind == kind,
                    onClick = { onSelect(kind) },
                    label = { Text(kind.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Button(onClick = onGenerate) { Text("Generate as source") }
    }
}

@Composable
private fun ProcessCard(
    state: LabUiState,
    onSelectPreset: (com.echidna.app.model.Preset) -> Unit,
    onProcess: () -> Unit,
    onPlayDry: () -> Unit,
    onPlayWet: () -> Unit,
    onStopPlayback: () -> Unit
) {
    SectionCard("4 · Process + A/B", "Run the source through a preset on the real engine, then compare dry vs wet.") {
        if (!state.hasDrySource) {
            Text("Record audio or generate a test tone first.", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        Text("Preset", style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                state.presets.chunked(2).forEach { rowPresets ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowPresets.forEach { preset ->
                            FilterChip(
                                selected = state.selectedPresetId == preset.id,
                                onClick = { onSelectPreset(preset) },
                                label = { Text(preset.name, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        }
        Button(onClick = onProcess, enabled = !state.processing && state.engineAvailable) {
            if (state.processing) {
                CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (state.processing) "Processing…" else "Process with preset")
        }
        if (!state.engineAvailable) {
            Text(
                "The native DSP engine is not packaged in this build, so wet output cannot be produced here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        state.dryWaveform?.let { dry ->
            Text("Dry (input)", style = MaterialTheme.typography.labelMedium)
            WaveformView(dry.peaks, MaterialTheme.colorScheme.primary)
            LevelSummary("Dry", dry)
        }
        state.wetWaveform?.let { wet ->
            Text("Wet (transformed)", style = MaterialTheme.typography.labelMedium)
            WaveformView(wet.peaks, MaterialTheme.colorScheme.tertiary)
            LevelSummary("Wet", wet)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaybackRow(state.playback == PlaybackTarget.DRY, onPlayDry, onStopPlayback, "A: Dry")
                PlaybackRow(state.playback == PlaybackTarget.WET, onPlayWet, onStopPlayback, "B: Wet")
            }
        }
    }
}

@Composable
private fun RealtimeCard(
    state: LabUiState,
    onConfirmHeadphones: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    SectionCard(
        "5 · Realtime voice transform",
        "Live mic → DSP → output. Added latency ≈ ${state.realtimeLatencyMs} ms."
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(
                "⚠ Use headphones. Without them, the speaker feeds back into the mic and can create a " +
                    "loud howl. Output is attenuated as a safeguard, but headphones are required.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.headphonesConfirmed, onCheckedChange = onConfirmHeadphones)
            Spacer(Modifier.width(8.dp))
            Text("I'm using headphones", style = MaterialTheme.typography.bodyMedium)
        }
        if (state.realtime == RealtimeState.RUNNING) {
            LevelMeter("In", state.realtimeInputDbfs)
            LevelMeter("Out", state.realtimeOutputDbfs)
            OutlinedButton(onClick = onStop) { Text("Stop monitoring") }
        } else {
            Button(
                onClick = onStart,
                enabled = state.permissionGranted && state.headphonesConfirmed && state.engineAvailable
            ) { Text("Start realtime monitor") }
        }
        Text(
            "Note: latency depends on the device's audio path; emulators add more. Realtime is best judged on real hardware.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---- Small building blocks -------------------------------------------------

@Composable
private fun PlaybackRow(playingThis: Boolean, onPlay: () -> Unit, onStop: () -> Unit, label: String) {
    if (playingThis) {
        OutlinedButton(onClick = onStop) { Text("Stop") }
    } else {
        Button(onClick = onPlay) { Text(label) }
    }
}

@Composable
private fun LevelSummary(label: String, wave: WaveformData) {
    val peak = if (wave.peakDbfs <= AudioAnalysis.SILENCE_DBFS) "silent" else "${wave.peakDbfs.roundToInt()} dB"
    val rms = if (wave.rmsDbfs <= AudioAnalysis.SILENCE_DBFS) "silent" else "${wave.rmsDbfs.roundToInt()} dB"
    AssistChip(
        onClick = {},
        label = {
            Text(
                "$label · ${"%.1f".format(wave.durationSeconds)}s · peak $peak · rms $rms",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
        }
    )
}

@Composable
private fun LevelMeter(label: String, dbfs: Float) {
    // Map -60..0 dBFS to 0..1.
    val fraction = ((dbfs + 60f) / 60f).coerceIn(0f, 1f)
    val meterColor = when {
        dbfs >= -3f -> MaterialTheme.colorScheme.error
        dbfs >= -12f -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                if (dbfs <= AudioAnalysis.SILENCE_DBFS) "—" else "${dbfs.roundToInt()} dBFS",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace
            )
        }
        val track = MaterialTheme.colorScheme.surfaceVariant
        Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
            drawLine(
                color = track, strokeWidth = size.height, cap = StrokeCap.Round,
                start = androidx.compose.ui.geometry.Offset(size.height / 2, size.height / 2),
                end = androidx.compose.ui.geometry.Offset(size.width - size.height / 2, size.height / 2)
            )
            if (fraction > 0f) {
                val usable = size.width - size.height
                drawLine(
                    color = meterColor, strokeWidth = size.height, cap = StrokeCap.Round,
                    start = androidx.compose.ui.geometry.Offset(size.height / 2, size.height / 2),
                    end = androidx.compose.ui.geometry.Offset(size.height / 2 + usable * fraction, size.height / 2)
                )
            }
        }
    }
}

@Composable
private fun WaveformView(peaks: FloatArray, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (peaks.isEmpty()) return@Canvas
            val mid = size.height / 2f
            val step = size.width / peaks.size
            peaks.forEachIndexed { i, p ->
                val h = (p.coerceIn(0f, 1f)) * (size.height / 2f)
                val x = i * step + step / 2f
                drawLine(
                    color = color,
                    strokeWidth = (step * 0.7f).coerceAtLeast(1f),
                    start = androidx.compose.ui.geometry.Offset(x, mid - h),
                    end = androidx.compose.ui.geometry.Offset(x, mid + h)
                )
            }
        }
    }
}
