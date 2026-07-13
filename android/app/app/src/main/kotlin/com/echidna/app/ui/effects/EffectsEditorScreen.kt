package com.echidna.app.ui.effects

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echidna.app.model.EffectModule
import com.echidna.app.model.MusicalKey
import com.echidna.app.model.MusicalScale
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.Locale

/**
 * Effects Editor — presents the active preset's effect chain as an ordered, numbered list of
 * expandable cards. Each card shows the stage name, a one-line description and an enable switch;
 * tapping it expands the parameter controls, each annotated with a helper description so the user
 * understands what it does. Stages the preset doesn't use can be added from the "Add effect"
 * section. The list is always shown in the native DSP processing order (gate → EQ → compressor →
 * pitch → formant → auto-tune → reverb → mix) so the on-screen order matches the audio path.
 */
@Composable
fun EffectsEditorScreen(viewModel: EffectsEditorViewModel) {
    val preset by viewModel.activePreset.collectAsStateWithLifecycle()
    val warnings by viewModel.warnings.collectAsStateWithLifecycle()

    val orderedModules = remember(preset.modules) {
        preset.modules.sortedBy { canonicalIndex(it.id) }
    }
    val availableToAdd = remember(preset.modules) {
        val present = preset.modules.map { it.id }.toSet()
        EffectsEditorViewModel.CANONICAL_ORDER.filter { it !in present }
    }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Effects Chain", style = MaterialTheme.typography.headlineSmall)
                // The chain is always that of the currently active preset; edits here are saved
                // back to it, and switching the active preset re-loads this list.
                Text(
                    text = "Editing preset: ${preset.name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Your voice flows through each enabled effect from top to bottom. " +
                        "Tap an effect to adjust its controls, use the switch to bypass it, " +
                        "or add another stage below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
        itemsIndexed(items = orderedModules, key = { _, module -> module.id }) { index, module ->
            EffectChainCard(
                stageNumber = index + 1,
                id = module.id,
                enabled = module.enabled,
                expanded = expandedId == module.id,
                onExpandToggle = {
                    expandedId = if (expandedId == module.id) null else module.id
                },
                onToggleEnabled = { viewModel.toggleModule(module.id, it) },
                onRemove = {
                    if (expandedId == module.id) expandedId = null
                    viewModel.removeModule(module.id)
                }
            ) {
                ModuleControls(module = module, viewModel = viewModel)
            }
        }
        item {
            AddEffectSection(available = availableToAdd, onAdd = { viewModel.addModule(it) })
        }
    }
}

/** Dispatches to the parameter-control body for a given effect module. */
@Composable
private fun ModuleControls(module: EffectModule, viewModel: EffectsEditorViewModel) {
    when (module) {
        is EffectModule.Gate -> GateControls(module) { threshold, attack, release, hysteresis ->
            viewModel.updateGate(threshold, attack, release, hysteresis)
        }
        is EffectModule.Equalizer -> EqControls(
            module = module,
            onBandCount = { viewModel.updateEqualizerBandCount(it) },
            onBandChange = { index, freq, gain, q -> viewModel.updateEqualizerBand(index, freq, gain, q) }
        )
        is EffectModule.Compressor -> CompressorControls(module) { mode, threshold, ratio, knee, attack, release, makeup ->
            viewModel.updateCompressor(mode, threshold, ratio, knee, attack, release, makeup)
        }
        is EffectModule.Pitch -> PitchControls(module) { semitone, cents, quality, preserve ->
            viewModel.updatePitch(semitone, cents, quality, preserve)
        }
        is EffectModule.Formant -> FormantControls(module) { cents, assist ->
            viewModel.updateFormant(cents, assist)
        }
        is EffectModule.AutoTune -> AutoTuneControls(module) { key, scale, retune, humanize, flex, preserve, snap ->
            viewModel.updateAutoTune(key, scale, retune, humanize, flex, preserve, snap)
        }
        is EffectModule.Reverb -> ReverbControls(module) { room, damp, preDelay, mix ->
            viewModel.updateReverb(room, damp, preDelay, mix)
        }
        is EffectModule.Mix -> MixControls(module) { dryWet, output ->
            viewModel.updateMix(dryWet, output)
        }
    }
}

/**
 * Reusable expandable card for one stage in the chain. Collapsed it shows a stage number, the
 * effect name (with an info tooltip), a one-line description and an enable switch. Tapping the
 * header expands the parameter [controls] plus a fuller description and a remove action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EffectChainCard(
    stageNumber: Int,
    id: String,
    enabled: Boolean,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit,
    controls: @Composable () -> Unit
) {
    val meta = effectMeta(id)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandToggle() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StageNumberBadge(number = stageNumber, active = enabled)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = meta.title, style = MaterialTheme.typography.titleMedium)
                        InfoTooltip(text = meta.longDescription)
                    }
                    Text(
                        text = meta.shortDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggleEnabled)
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = meta.longDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    controls()
                    TextButton(onClick = onRemove) { Text("Remove from chain") }
                }
            }
        }
    }
}

@Composable
private fun StageNumberBadge(number: Int, active: Boolean) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(text = number.toString(), style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/** Small info affordance that reveals a plain tooltip explaining the effect. */
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
            contentDescription = "About this effect",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(16.dp)
                .clickable { scope.launch { tooltipState.show() } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddEffectSection(available: List<String>, onAdd: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add effect", style = MaterialTheme.typography.titleMedium)
            if (available.isEmpty()) {
                Text(
                    "Every effect is already in the chain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Stages are inserted at their position in the signal path.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    available.forEach { id ->
                        AssistChip(
                            onClick = { onAdd(id) },
                            label = { Text(effectMeta(id).title) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

// --- Per-effect control bodies (descriptions on every parameter) ------------------------------

@Composable
private fun GateControls(module: EffectModule.Gate, onChange: (Float, Float, Float, Float) -> Unit) {
    SliderWithValue("Threshold", module.thresholdDb, -80f..-20f, "dB",
        "Audio quieter than this is muted. Raise it to cut more background noise.") {
        onChange(it, module.attackMs, module.releaseMs, module.hysteresisDb)
    }
    SliderWithValue("Attack", module.attackMs, 1f..50f, "ms",
        "How quickly the gate opens once you start speaking.") {
        onChange(module.thresholdDb, it, module.releaseMs, module.hysteresisDb)
    }
    SliderWithValue("Release", module.releaseMs, 20f..500f, "ms",
        "How long the gate stays open after you stop speaking.") {
        onChange(module.thresholdDb, module.attackMs, it, module.hysteresisDb)
    }
    SliderWithValue("Hysteresis", module.hysteresisDb, 0f..12f, "dB",
        "Gap between the open and close levels; higher values stop the gate chattering.") {
        onChange(module.thresholdDb, module.attackMs, module.releaseMs, it)
    }
}

@Composable
private fun EqControls(
    module: EffectModule.Equalizer,
    onBandCount: (Int) -> Unit,
    onBandChange: (Int, Float, Float, Float) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = "Bands: ${module.bandCount}")
        listOf(3, 5, 8).forEach { count ->
            TextButton(onClick = { onBandCount(count) }) { Text("$count") }
        }
    }
    Text(
        "Number of adjustable frequency bands.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    module.bands.take(module.bandCount).forEachIndexed { index, band ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Band ${index + 1}", style = MaterialTheme.typography.labelLarge)
            SliderWithValue("Freq", band.frequency, 20f..12000f, "Hz",
                "Center frequency this band boosts or cuts.") { freq ->
                onBandChange(index, freq, band.gainDb, band.q)
            }
            SliderWithValue("Gain", band.gainDb, -12f..12f, "dB",
                "Boost (+) or cut (−) at this frequency.") { gain ->
                onBandChange(index, band.frequency, gain, band.q)
            }
            SliderWithValue("Q", band.q, 0.3f..10f, "",
                "Bandwidth: higher is narrower and more focused.") { q ->
                onBandChange(index, band.frequency, band.gainDb, q)
            }
        }
    }
}

@Composable
private fun CompressorControls(
    module: EffectModule.Compressor,
    onChange: (EffectModule.CompressorMode, Float, Float, Float, Float, Float, Float) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange(EffectModule.CompressorMode.MANUAL, module.thresholdDb, module.ratio, module.kneeDb, module.attackMs, module.releaseMs, module.makeupGainDb) }) {
            Text("Manual")
        }
        TextButton(onClick = { onChange(EffectModule.CompressorMode.AUTO, module.thresholdDb, module.ratio, module.kneeDb, module.attackMs, module.releaseMs, module.makeupGainDb) }) {
            Text("Auto")
        }
        Text(text = module.mode.name)
    }
    Text(
        "Auto sets the level for you; Manual uses the controls below.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SliderWithValue("Threshold", module.thresholdDb, -60f..-5f, "dB",
        "Level above which loud parts get turned down.") {
        onChange(module.mode, it, module.ratio, module.kneeDb, module.attackMs, module.releaseMs, module.makeupGainDb)
    }
    SliderWithValue("Ratio", module.ratio, 1.2f..6f, ":1",
        "How hard loud parts are turned down once past the threshold.") {
        onChange(module.mode, module.thresholdDb, it, module.kneeDb, module.attackMs, module.releaseMs, module.makeupGainDb)
    }
    SliderWithValue("Knee", module.kneeDb, 0f..12f, "dB",
        "Softness of the transition around the threshold.") {
        onChange(module.mode, module.thresholdDb, module.ratio, it, module.attackMs, module.releaseMs, module.makeupGainDb)
    }
    SliderWithValue("Attack", module.attackMs, 1f..50f, "ms",
        "How quickly it reacts to loud peaks.") {
        onChange(module.mode, module.thresholdDb, module.ratio, module.kneeDb, it, module.releaseMs, module.makeupGainDb)
    }
    SliderWithValue("Release", module.releaseMs, 20f..500f, "ms",
        "How quickly it recovers after a loud part.") {
        onChange(module.mode, module.thresholdDb, module.ratio, module.kneeDb, module.attackMs, it, module.makeupGainDb)
    }
    SliderWithValue("Makeup", module.makeupGainDb, 0f..12f, "dB",
        "Extra gain to make up for the level it removed.") {
        onChange(module.mode, module.thresholdDb, module.ratio, module.kneeDb, module.attackMs, module.releaseMs, it)
    }
}

@Composable
private fun PitchControls(
    module: EffectModule.Pitch,
    onChange: (Float, Float, EffectModule.PitchQuality, Boolean) -> Unit
) {
    SliderWithValue("Semitones", module.semitones, -12f..12f, "",
        "Shifts your voice up or down. 12 semitones = one octave.") {
        onChange(it, module.cents, module.quality, module.preserveFormants)
    }
    SliderWithValue("Fine", module.cents, -100f..100f, "cents",
        "Fine tuning between semitones (100 cents = 1 semitone).") {
        onChange(module.semitones, it, module.quality, module.preserveFormants)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Quality: ${module.quality}")
        TextButton(onClick = { onChange(module.semitones, module.cents, EffectModule.PitchQuality.LL, module.preserveFormants) }) { Text("LL") }
        TextButton(onClick = { onChange(module.semitones, module.cents, EffectModule.PitchQuality.HQ, module.preserveFormants) }) { Text("HQ") }
    }
    Text(
        "LL = low latency for live calls; HQ = better fidelity with a little more delay.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SwitchRow(
        label = "Preserve Formants",
        description = "Keeps your natural timbre so the shift sounds less chipmunk or monster.",
        checked = module.preserveFormants
    ) { onChange(module.semitones, module.cents, module.quality, it) }
}

@Composable
private fun FormantControls(module: EffectModule.Formant, onChange: (Float, Boolean) -> Unit) {
    SliderWithValue("Shift", module.cents, -600f..600f, "cents",
        "Moves vocal resonance without changing pitch: negative = bigger/deeper, positive = smaller/brighter.") {
        onChange(it, module.intelligibilityAssist)
    }
    SwitchRow(
        label = "Intelligibility Assist",
        description = "Keeps speech clear and articulate at extreme shift settings.",
        checked = module.intelligibilityAssist
    ) { onChange(module.cents, it) }
}

@Composable
private fun AutoTuneControls(
    module: EffectModule.AutoTune,
    onChange: (MusicalKey, MusicalScale, Float, Float, Float, Boolean, Float) -> Unit
) {
    Dropdown(
        label = "Key",
        description = "Musical key your voice is tuned toward.",
        options = MusicalKey.values().toList(),
        selected = module.key,
        labelFor = { it.displayName }
    ) {
        onChange(it, module.scale, module.retuneMs, module.humanizePercent, module.flexTunePercent, module.formantPreserve, module.snapStrengthPercent)
    }
    Dropdown(
        label = "Scale",
        description = "Set of notes the pitch is allowed to snap to.",
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
    SliderWithValue("Retune", module.retuneMs, 1f..200f, "ms",
        "How fast pitch snaps to the note. Lower is tighter and more robotic.") {
        onChange(module.key, module.scale, it, module.humanizePercent, module.flexTunePercent, module.formantPreserve, module.snapStrengthPercent)
    }
    SliderWithValue("Humanize", module.humanizePercent, 0f..100f, "%",
        "Adds natural variation so correction sounds less robotic.") {
        onChange(module.key, module.scale, module.retuneMs, it, module.flexTunePercent, module.formantPreserve, module.snapStrengthPercent)
    }
    SliderWithValue("Flex-Tune", module.flexTunePercent, 0f..100f, "%",
        "Leaves expressive pitch slides untouched while still correcting held notes.") {
        onChange(module.key, module.scale, module.retuneMs, module.humanizePercent, it, module.formantPreserve, module.snapStrengthPercent)
    }
    SwitchRow(
        label = "Preserve Formants",
        description = "Keeps your natural timbre while correcting pitch.",
        checked = module.formantPreserve
    ) {
        onChange(module.key, module.scale, module.retuneMs, module.humanizePercent, module.flexTunePercent, it, module.snapStrengthPercent)
    }
    SliderWithValue("Snap Strength", module.snapStrengthPercent, 0f..100f, "%",
        "How strongly notes are pulled to perfect pitch.") {
        onChange(module.key, module.scale, module.retuneMs, module.humanizePercent, module.flexTunePercent, module.formantPreserve, it)
    }
}

@Composable
private fun ReverbControls(module: EffectModule.Reverb, onChange: (Float, Float, Float, Float) -> Unit) {
    SliderWithValue("Room", module.roomSize, 0f..100f, "",
        "Size of the simulated space — larger sounds more spacious.") {
        onChange(it, module.damping, module.preDelayMs, module.mixPercent)
    }
    SliderWithValue("Damping", module.damping, 0f..100f, "",
        "How quickly high frequencies fade in the reverb tail.") {
        onChange(module.roomSize, it, module.preDelayMs, module.mixPercent)
    }
    SliderWithValue("Pre-Delay", module.preDelayMs, 0f..40f, "ms",
        "Gap before the reverb starts, keeping your voice up front.") {
        onChange(module.roomSize, module.damping, it, module.mixPercent)
    }
    SliderWithValue("Mix", module.mixPercent, 0f..50f, "%",
        "How much room/echo is blended into your voice.") {
        onChange(module.roomSize, module.damping, module.preDelayMs, it)
    }
}

@Composable
private fun MixControls(module: EffectModule.Mix, onChange: (Float, Float) -> Unit) {
    SliderWithValue("Dry/Wet", module.dryWetPercent, 0f..100f, "%",
        "Balance of the processed (wet) voice against your original (dry) voice.") {
        onChange(it, module.outputGainDb)
    }
    SliderWithValue("Output Gain", module.outputGainDb, -12f..12f, "dB",
        "Overall output level of the final voice.") {
        onChange(module.dryWetPercent, it)
    }
}

// --- Shared control primitives ----------------------------------------------------------------

@Composable
private fun SliderWithValue(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String = "",
    description: String? = null,
    onChange: (Float) -> Unit
) {
    Column {
        val display = if (range.endInclusive - range.start <= 10f) {
            String.format(Locale.US, "%.1f", value)
        } else {
            value.roundToInt().toString()
        }
        val suffix = if (unit.isBlank()) "" else if (unit.startsWith(":")) unit else " $unit"
        Text(text = "$label: $display$suffix")
        Slider(value = value, onValueChange = onChange, valueRange = range)
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label)
            Switch(checked = checked, onCheckedChange = onChange)
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun <T> Dropdown(
    label: String,
    description: String,
    options: List<T>,
    selected: T,
    labelFor: (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(text = "$label: ${labelFor(selected)}")
        OutlinedButton(onClick = { expanded = true }) { Text("Select") }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

// --- Effect metadata --------------------------------------------------------------------------

private data class EffectMeta(
    val title: String,
    val shortDescription: String,
    val longDescription: String
)

private fun canonicalIndex(id: String): Int =
    EffectsEditorViewModel.CANONICAL_ORDER.indexOf(id)
        .let { if (it < 0) EffectsEditorViewModel.CANONICAL_ORDER.size else it }

private fun effectMeta(id: String): EffectMeta = when (id) {
    "gate" -> EffectMeta(
        "Noise Gate",
        "Mutes quiet background noise",
        "Silences audio below a set level to cut background noise and hiss between words."
    )
    "eq" -> EffectMeta(
        "Equalizer",
        "Shapes your tone by frequency",
        "Boosts or cuts specific frequency ranges to make your voice brighter, warmer or thinner."
    )
    "comp" -> EffectMeta(
        "Compressor / AGC",
        "Evens out loud and quiet parts",
        "Turns down the loudest parts and lifts the quiet ones for a steadier, more consistent level."
    )
    "pitch" -> EffectMeta(
        "Pitch Shift",
        "Raises or lowers your pitch",
        "Shifts your voice up or down in semitones (−12 to +12) without changing its speed."
    )
    "formant" -> EffectMeta(
        "Formant",
        "Makes your voice bigger or smaller",
        "Moves vocal resonance to sound bigger or smaller without changing the pitch."
    )
    "autotune" -> EffectMeta(
        "Auto-Tune",
        "Snaps pitch to a musical key",
        "Corrects your pitch to the nearest note in a chosen key and scale, from subtle to robotic."
    )
    "reverb" -> EffectMeta(
        "Reverb",
        "Adds room ambience and echo",
        "Blends in the sound of a simulated space, from a small room to a large hall."
    )
    "mix" -> EffectMeta(
        "Dry/Wet Mix",
        "Blends processed and original voice",
        "Sets how much of the processed voice you hear versus your original, plus the output level."
    )
    else -> EffectMeta(id, "", "")
}
