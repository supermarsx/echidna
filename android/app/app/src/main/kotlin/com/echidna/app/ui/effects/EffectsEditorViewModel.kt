package com.echidna.app.ui.effects

import androidx.lifecycle.ViewModel
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.EffectModule
import com.echidna.app.model.MusicalKey
import com.echidna.app.model.MusicalScale
import com.echidna.app.model.Preset
import com.echidna.app.model.PresetWarning
import kotlinx.coroutines.flow.StateFlow

class EffectsEditorViewModel : ViewModel() {
    private val repo = ControlStateRepository

    val activePreset: StateFlow<Preset> = repo.activePreset
    val warnings: StateFlow<List<PresetWarning>> = repo.presetWarnings

    fun toggleModule(moduleId: String, enabled: Boolean) = updateModule(moduleId) {
        when (it) {
            is EffectModule.Gate -> it.copy(enabled = enabled)
            is EffectModule.Equalizer -> it.copy(enabled = enabled)
            is EffectModule.Compressor -> it.copy(enabled = enabled)
            is EffectModule.Pitch -> it.copy(enabled = enabled)
            is EffectModule.Formant -> it.copy(enabled = enabled)
            is EffectModule.AutoTune -> it.copy(enabled = enabled)
            is EffectModule.Reverb -> it.copy(enabled = enabled)
            is EffectModule.Mix -> it.copy(enabled = enabled)
        }
    }

    fun updateGate(threshold: Float, attack: Float, release: Float, hysteresis: Float) = updateModule("gate") {
        (it as? EffectModule.Gate)?.copy(
            thresholdDb = threshold,
            attackMs = attack,
            releaseMs = release,
            hysteresisDb = hysteresis
        ) ?: it
    }

    fun updateEqualizerBandCount(count: Int) = updateModule("eq") {
        (it as? EffectModule.Equalizer)?.let { eq ->
            val bands = if (eq.bands.size >= count) {
                eq.bands.take(count)
            } else {
                eq.bands + List(count - eq.bands.size) {
                    com.echidna.app.model.Band(frequency = 100f * (it + 1), gainDb = 0f, q = 1f)
                }
            }
            eq.copy(bandCount = count, bands = bands)
        } ?: it
    }

    fun updateEqualizerBand(index: Int, frequency: Float, gain: Float, q: Float) = updateModule("eq") {
        (it as? EffectModule.Equalizer)?.let { eq ->
            val bands = eq.bands.toMutableList()
            if (index in bands.indices) {
                bands[index] = bands[index].copy(frequency = frequency, gainDb = gain, q = q)
            }
            eq.copy(bands = bands)
        } ?: it
    }

    fun updateCompressor(
        mode: EffectModule.CompressorMode,
        threshold: Float,
        ratio: Float,
        knee: Float,
        attack: Float,
        release: Float,
        makeup: Float
    ) = updateModule("comp") {
        (it as? EffectModule.Compressor)?.copy(
            mode = mode,
            thresholdDb = threshold,
            ratio = ratio,
            kneeDb = knee,
            attackMs = attack,
            releaseMs = release,
            makeupGainDb = makeup
        ) ?: it
    }

    fun updatePitch(semitones: Float, cents: Float, quality: EffectModule.PitchQuality, preserve: Boolean) =
        updateModule("pitch") {
            (it as? EffectModule.Pitch)?.copy(
                semitones = semitones,
                cents = cents,
                quality = quality,
                preserveFormants = preserve
            ) ?: it
        }

    fun updateFormant(cents: Float, intelligibility: Boolean) = updateModule("formant") {
        (it as? EffectModule.Formant)?.copy(cents = cents, intelligibilityAssist = intelligibility) ?: it
    }

    fun updateAutoTune(
        key: MusicalKey,
        scale: MusicalScale,
        retune: Float,
        humanize: Float,
        flex: Float,
        preserveFormant: Boolean,
        snap: Float
    ) = updateModule("autotune") {
        (it as? EffectModule.AutoTune)?.copy(
            key = key,
            scale = scale,
            retuneMs = retune,
            humanizePercent = humanize,
            flexTunePercent = flex,
            formantPreserve = preserveFormant,
            snapStrengthPercent = snap
        ) ?: it
    }

    fun updateReverb(room: Float, damping: Float, preDelay: Float, mix: Float) = updateModule("reverb") {
        (it as? EffectModule.Reverb)?.copy(
            roomSize = room,
            damping = damping,
            preDelayMs = preDelay,
            mixPercent = mix
        ) ?: it
    }

    fun updateMix(dryWet: Float, outputGain: Float) = updateModule("mix") {
        (it as? EffectModule.Mix)?.copy(dryWetPercent = dryWet, outputGainDb = outputGain) ?: it
    }

    private fun updateModule(moduleId: String, transform: (EffectModule) -> EffectModule) {
        val preset = activePreset.value
        val modules = preset.modules.map { module ->
            if (module.id == moduleId) transform(module) else module
        }
        repo.updatePreset(preset.copy(modules = modules))
    }
}
