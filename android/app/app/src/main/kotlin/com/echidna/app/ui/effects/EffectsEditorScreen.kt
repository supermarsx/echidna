package com.echidna.app.ui.effects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echidna.app.model.EffectModule
import com.echidna.app.model.MusicalKey
import com.echidna.app.model.MusicalScale
import kotlin.math.roundToInt
import java.util.Locale

@Composable
fun EffectsEditorScreen(viewModel: EffectsEditorViewModel) {
    val preset by viewModel.activePreset.collectAsStateWithLifecycle()
    val warnings by viewModel.warnings.collectAsStateWithLifecycle()
    val modules = preset.modules

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Text(text = "Effects Chain", style = MaterialTheme.typography.headlineSmall) }
        if (warnings.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Preset Warnings", style = MaterialTheme.typography.titleMedium)
                        warnings.forEach { warning ->
                            val color = when (warning.severity) {
                                com.echidna.app.model.WarningSeverity.INFO -> MaterialTheme.colorScheme.primary
                                com.echidna.app.model.WarningSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                                com.echidna.app.model.WarningSeverity.CRITICAL -> MaterialTheme.colorScheme.error
                            }
                            Text(warning.message, color = color, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        items(items = modules, key = { it.id }) { module ->
            when (module) {
                    is EffectModule.Gate -> GateCard(module, onToggle = { viewModel.toggleModule(module.id, it) }) { threshold, attack, release, hysteresis ->
                        viewModel.updateGate(threshold, attack, release, hysteresis)
                    }
                    is EffectModule.Equalizer -> EqCard(
                        module = module,
                        onToggle = { viewModel.toggleModule(module.id, it) },
                        onBandCount = { viewModel.updateEqualizerBandCount(it) },
                        onBandChange = { index, freq, gain, q ->
                            viewModel.updateEqualizerBand(index, freq, gain, q)
                        }
                    )
                    is EffectModule.Compressor -> CompressorCard(module, onToggle = { viewModel.toggleModule(module.id, it) }) {
                        mode, threshold, ratio, knee, attack, release, makeup ->
                        viewModel.updateCompressor(mode, threshold, ratio, knee, attack, release, makeup)
                    }
                    is EffectModule.Pitch -> PitchCard(module, onToggle = { viewModel.toggleModule(module.id, it) }) { semitone, cents, quality, preserve ->
                        viewModel.updatePitch(semitone, cents, quality, preserve)
                    }
                    is EffectModule.Formant -> FormantCard(module, onToggle = { viewModel.toggleModule(module.id, it) }) { cents, assist ->
                        viewModel.updateFormant(cents, assist)
                    }
                    is EffectModule.AutoTune -> AutoTuneCard(module, onToggle = { viewModel.toggleModule(module.id, it) }) {
                        key, scale, retune, humanize, flex, preserve, snap ->
                        viewModel.updateAutoTune(key, scale, retune, humanize, flex, preserve, snap)
                    }
                    is EffectModule.Reverb -> ReverbCard(module, onToggle = { viewModel.toggleModule(module.id, it) }) { room, damp, preDelay, mix ->
                        viewModel.updateReverb(room, damp, preDelay, mix)
                    }
                    is EffectModule.Mix -> MixCard(module, onToggle = { viewModel.toggleModule(module.id, it) }) { dryWet, output ->
                        viewModel.updateMix(dryWet, output)
                    }
            }
        }
    }
}

@Composable
private fun ModuleHeader(title: String, enabled: Boolean, onToggle: (Boolean) -> Unit, subtitle: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun GateCard(module: EffectModule.Gate, onToggle: (Boolean) -> Unit, onChange: (Float, Float, Float, Float) -> Unit) {
    Card { Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModuleHeader("Noise Gate", module.enabled, onToggle, "Threshold −80…−20 dBFS")
        SliderWithValue(label = "Threshold", value = module.thresholdDb, range = -80f..-20f) { onChange(it, module.attackMs, module.releaseMs, module.hysteresisDb) }
        SliderWithValue(label = "Attack", value = module.attackMs, range = 1f..50f) { onChange(module.thresholdDb, it, module.releaseMs, module.hysteresisDb) }
        SliderWithValue(label = "Release", value = module.releaseMs, range = 20f..500f) { onChange(module.thresholdDb, module.attackMs, it, module.hysteresisDb) }
        SliderWithValue(label = "Hysteresis", value = module.hysteresisDb, range = 0f..12f) { onChange(module.thresholdDb, module.attackMs, module.releaseMs, it) }
    } }
}

@Composable
private fun EqCard(
    module: EffectModule.Equalizer,
    onToggle: (Boolean) -> Unit,
    onBandCount: (Int) -> Unit,
    onBandChange: (Int, Float, Float, Float) -> Unit
) {
    Card { Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModuleHeader("Equalizer", module.enabled, onToggle, "Bands 3/5/8")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Bands: ${module.bandCount}")
            listOf(3, 5, 8).forEach { count ->
                TextButton(onClick = { onBandCount(count) }) { Text("$count") }
            }
        }
        module.bands.take(module.bandCount).forEachIndexed { index, band ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Band ${index + 1}")
                SliderWithValue("Freq", band.frequency, 20f..12000f) { freq ->
                    onBandChange(index, freq, band.gainDb, band.q)
                }
                SliderWithValue("Gain", band.gainDb, -12f..12f) { gain ->
                    onBandChange(index, band.frequency, gain, band.q)
                }
                SliderWithValue("Q", band.q, 0.3f..10f) { q ->
                    onBandChange(index, band.frequency, band.gainDb, q)
                }
            }
        }
    } }
}

@Composable
private fun CompressorCard(
    module: EffectModule.Compressor,
    onToggle: (Boolean) -> Unit,
    onChange: (EffectModule.CompressorMode, Float, Float, Float, Float, Float, Float) -> Unit
) {
    Card { Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModuleHeader("Compressor/AGC", module.enabled, onToggle, "Threshold −60…−5 dB")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onChange(EffectModule.CompressorMode.MANUAL, module.thresholdDb, module.ratio, module.kneeDb, module.attackMs, module.releaseMs, module.makeupGainDb) }) {
                Text("Manual")
            }
            TextButton(onClick = { onChange(EffectModule.CompressorMode.AUTO, module.thresholdDb, module.ratio, module.kneeDb, module.attackMs, module.releaseMs, module.makeupGainDb) }) {
                Text("Auto")
            }
            Text(text = module.mode.name)
        }
        SliderWithValue("Threshold", module.thresholdDb, -60f..-5f) {
            onChange(module.mode, it, module.ratio, module.kneeDb, module.attackMs, module.releaseMs, module.makeupGainDb)
        }
        SliderWithValue("Ratio", module.ratio, 1.2f..6f) {
            onChange(module.mode, module.thresholdDb, it, module.kneeDb, module.attackMs, module.releaseMs, module.makeupGainDb)
        }
        SliderWithValue("Knee", module.kneeDb, 0f..12f) {
            onChange(module.mode, module.thresholdDb, module.ratio, it, module.attackMs, module.releaseMs, module.makeupGainDb)
        }
        SliderWithValue("Attack", module.attackMs, 1f..50f) {
            onChange(module.mode, module.thresholdDb, module.ratio, module.kneeDb, it, module.releaseMs, module.makeupGainDb)
        }
        SliderWithValue("Release", module.releaseMs, 20f..500f) {
            onChange(module.mode, module.thresholdDb, module.ratio, module.kneeDb, module.attackMs, it, module.makeupGainDb)
        }
        SliderWithValue("Makeup", module.makeupGainDb, 0f..12f) {
            onChange(module.mode, module.thresholdDb, module.ratio, module.kneeDb, module.attackMs, module.releaseMs, it)
        }
    } }
}

@Composable
private fun PitchCard(
    module: EffectModule.Pitch,
    onToggle: (Boolean) -> Unit,
    onChange: (Float, Float, EffectModule.PitchQuality, Boolean) -> Unit
) {
    Card { Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModuleHeader("Pitch", module.enabled, onToggle, "Semitones −12…+12")
        SliderWithValue("Semitones", module.semitones, -12f..12f) {
            onChange(it, module.cents, module.quality, module.preserveFormants)
        }
        SliderWithValue("Fine", module.cents, -100f..100f) {
            onChange(module.semitones, it, module.quality, module.preserveFormants)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Quality: ${module.quality}")
            TextButton(onClick = { onChange(module.semitones, module.cents, EffectModule.PitchQuality.LL, module.preserveFormants) }) { Text("LL") }
            TextButton(onClick = { onChange(module.semitones, module.cents, EffectModule.PitchQuality.HQ, module.preserveFormants) }) { Text("HQ") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Preserve Formants")
            Switch(checked = module.preserveFormants, onCheckedChange = { onChange(module.semitones, module.cents, module.quality, it) })
        }
    } }
}

@Composable
private fun FormantCard(module: EffectModule.Formant, onToggle: (Boolean) -> Unit, onChange: (Float, Boolean) -> Unit) {
    Card { Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModuleHeader("Formant", module.enabled, onToggle, "Cents −600…+600")
        SliderWithValue("Shift", module.cents, -600f..600f) { onChange(it, module.intelligibilityAssist) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Intelligibility Assist")
            Switch(checked = module.intelligibilityAssist, onCheckedChange = { onChange(module.cents, it) })
        }
    } }
}

@Composable
private fun AutoTuneCard(
    module: EffectModule.AutoTune,
    onToggle: (Boolean) -> Unit,
    onChange: (MusicalKey, MusicalScale, Float, Float, Float, Boolean, Float) -> Unit
) {
    Card { Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModuleHeader("Auto-Tune", module.enabled, onToggle, "Retune 1…200 ms")
        Dropdown(
            label = "Key",
            options = MusicalKey.values().toList(),
            selected = module.key,
            labelFor = { it.displayName }
        ) {
            onChange(it, module.scale, module.retuneMs, module.humanizePercent, module.flexTunePercent, module.formantPreserve, module.snapStrengthPercent)
        }
        Dropdown(
            label = "Scale",
            options = MusicalScale.values().toList(),
            selected = module.scale,
            labelFor = { scale ->
                scale.name.lowercase().replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
                }
            }
        ) {
            onChange(module.key, it, module.retuneMs, module.humanizePercent, module.flexTunePercent, module.formantPreserve, module.snapStrengthPercent)
        }
        SliderWithValue("Retune", module.retuneMs, 1f..200f) {
            onChange(module.key, module.scale, it, module.humanizePercent, module.flexTunePercent, module.formantPreserve, module.snapStrengthPercent)
        }
        SliderWithValue("Humanize", module.humanizePercent, 0f..100f) {
            onChange(module.key, module.scale, module.retuneMs, it, module.flexTunePercent, module.formantPreserve, module.snapStrengthPercent)
        }
        SliderWithValue("Flex-Tune", module.flexTunePercent, 0f..100f) {
            onChange(module.key, module.scale, module.retuneMs, module.humanizePercent, it, module.formantPreserve, module.snapStrengthPercent)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Preserve Formants")
            Switch(checked = module.formantPreserve, onCheckedChange = {
                onChange(module.key, module.scale, module.retuneMs, module.humanizePercent, module.flexTunePercent, it, module.snapStrengthPercent)
            })
        }
        SliderWithValue("Snap Strength", module.snapStrengthPercent, 0f..100f) {
            onChange(module.key, module.scale, module.retuneMs, module.humanizePercent, module.flexTunePercent, module.formantPreserve, it)
        }
    } }
}

@Composable
private fun ReverbCard(module: EffectModule.Reverb, onToggle: (Boolean) -> Unit, onChange: (Float, Float, Float, Float) -> Unit) {
    Card { Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModuleHeader("Reverb", module.enabled, onToggle)
        SliderWithValue("Room", module.roomSize, 0f..100f) { onChange(it, module.damping, module.preDelayMs, module.mixPercent) }
        SliderWithValue("Damping", module.damping, 0f..100f) { onChange(module.roomSize, it, module.preDelayMs, module.mixPercent) }
        SliderWithValue("Pre-Delay", module.preDelayMs, 0f..40f) { onChange(module.roomSize, module.damping, it, module.mixPercent) }
        SliderWithValue("Mix", module.mixPercent, 0f..50f) { onChange(module.roomSize, module.damping, module.preDelayMs, it) }
    } }
}

@Composable
private fun MixCard(module: EffectModule.Mix, onToggle: (Boolean) -> Unit, onChange: (Float, Float) -> Unit) {
    Card { Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModuleHeader("Mix", module.enabled, onToggle)
        SliderWithValue("Dry/Wet", module.dryWetPercent, 0f..100f) { onChange(it, module.outputGainDb) }
        SliderWithValue("Output Gain", module.outputGainDb, -12f..12f) { onChange(module.dryWetPercent, it) }
    } }
}

@Composable
private fun SliderWithValue(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        val display = if (range.endInclusive - range.start <= 10f) {
            String.format(Locale.US, "%.1f", value)
        } else {
            value.roundToInt().toString()
        }
        Text(text = "$label: $display")
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun <T> Dropdown(
    label: String,
    options: List<T>,
    selected: T,
    labelFor: (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(text = "$label: ${labelFor(selected)}")
        OutlinedButton(onClick = { expanded = true }) { Text("Select") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(labelFor(option)) }, onClick = {
                    onSelect(option)
                    expanded = false
                })
            }
        }
    }
}
