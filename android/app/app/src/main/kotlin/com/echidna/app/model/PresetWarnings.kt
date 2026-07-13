package com.echidna.app.model

data class PresetWarning(val message: String, val severity: WarningSeverity)

enum class WarningSeverity { INFO, WARNING, CRITICAL }

object PresetValidator {
    fun evaluate(preset: Preset): List<PresetWarning> {
        val warnings = mutableListOf<PresetWarning>()
        preset.modules.forEach { module ->
            when (module) {
                is EffectModule.Gate -> {
                    if (module.thresholdDb <= -78f || module.thresholdDb >= -22f) {
                        warnings += PresetWarning("Gate threshold near extreme", WarningSeverity.WARNING)
                    }
                    if (module.attackMs <= 1f) warnings += PresetWarning("Gate attack extremely fast", WarningSeverity.INFO)
                    if (module.releaseMs >= 450f) warnings += PresetWarning("Gate release very slow", WarningSeverity.INFO)
                }
                is EffectModule.Compressor -> {
                    if (module.thresholdDb <= -55f || module.thresholdDb >= -6f) {
                        warnings += PresetWarning("Compressor threshold extreme", WarningSeverity.WARNING)
                    }
                    if (module.ratio >= 5.5f) warnings += PresetWarning("Compression ratio very high", WarningSeverity.CRITICAL)
                }
                is EffectModule.Pitch -> {
                    if (module.semitones <= -10f || module.semitones >= 10f) {
                        warnings += PresetWarning("Pitch shift near limit", WarningSeverity.CRITICAL)
                    }
                }
                is EffectModule.Formant -> {
                    if (module.cents <= -550f || module.cents >= 550f) {
                        warnings += PresetWarning("Formant shift may sound unnatural", WarningSeverity.WARNING)
                    }
                }
                is EffectModule.AutoTune -> {
                    if (module.retuneMs <= 5f) warnings += PresetWarning("Auto-Tune retune speed is robotic", WarningSeverity.INFO)
                    if (module.humanizePercent <= 5f) warnings += PresetWarning("Auto-Tune humanize is minimal", WarningSeverity.INFO)
                }
                is EffectModule.Reverb -> {
                    if (module.mixPercent >= 48f) warnings += PresetWarning("Reverb mix is very wet", WarningSeverity.WARNING)
                }
                is EffectModule.Mix -> {
                    if (module.outputGainDb >= 11f) warnings += PresetWarning("Output gain near clipping", WarningSeverity.WARNING)
                }
                else -> Unit
            }
        }
        if (preset.dryWet >= 95) {
            warnings += PresetWarning("Dry/wet mix heavily favors wet signal", WarningSeverity.INFO)
        }
        return warnings
    }
}
