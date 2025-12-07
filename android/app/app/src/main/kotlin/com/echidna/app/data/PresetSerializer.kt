package com.echidna.app.data

import com.echidna.app.model.EffectModule
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.MusicalKey
import com.echidna.app.model.MusicalScale
import com.echidna.app.model.Preset
import org.json.JSONArray
import org.json.JSONObject

object PresetSerializer {
    fun toJson(preset: Preset): String {
        val root = JSONObject()
        root.put("name", preset.name)
        root.put("version", 1)
        val meta = JSONObject()
        if (preset.description != null) {
            meta.put("description", preset.description)
        }
        val tags = JSONArray()
        preset.tags.forEach { tags.put(it) }
        meta.put("tags", tags)
        root.put("meta", meta)

        val engine = JSONObject()
        engine.put("latencyMode", latencyModeString(preset.latencyMode))
        engine.put("blockMs", preset.latencyMode.targetMs)
        root.put("engine", engine)

        val modules = JSONArray()
        preset.modules.forEach { module ->
            modules.put(serializeModule(module, preset.dryWet))
        }
        // Ensure mix exists even if not in modules.
        if (preset.modules.none { it.id == "mix" }) {
            modules.put(JSONObject().apply {
                put("id", "mix")
                put("wet", preset.dryWet)
                put("outGain", 0.0)
                put("enabled", true)
            })
        }
        root.put("modules", modules)
        return root.toString()
    }

    private fun serializeModule(module: EffectModule, presetWet: Int): JSONObject =
        when (module) {
            is EffectModule.Gate -> JSONObject().apply {
                put("id", module.id)
                put("enabled", module.enabled)
                put("threshold", module.thresholdDb)
                put("attackMs", module.attackMs)
                put("releaseMs", module.releaseMs)
                put("hysteresis", module.hysteresisDb)
            }
            is EffectModule.Equalizer -> JSONObject().apply {
                put("id", module.id)
                put("enabled", module.enabled)
                val bands = JSONArray()
                module.bands.forEach { band ->
                    bands.put(JSONObject().apply {
                        put("f", band.frequency)
                        put("g", band.gainDb)
                        put("q", band.q)
                    })
                }
                put("bands", bands)
            }
            is EffectModule.Compressor -> JSONObject().apply {
                put("id", module.id)
                put("enabled", module.enabled)
                put("mode", if (module.mode == EffectModule.CompressorMode.AUTO) "auto" else "manual")
                put("threshold", module.thresholdDb)
                put("ratio", module.ratio)
                put("knee", module.kneeDb)
                put("attackMs", module.attackMs)
                put("releaseMs", module.releaseMs)
                put("makeup", module.makeupGainDb)
            }
            is EffectModule.Pitch -> JSONObject().apply {
                put("id", module.id)
                put("enabled", module.enabled)
                put("semitones", module.semitones)
                put("cents", module.cents)
                put("quality", if (module.quality == EffectModule.PitchQuality.LL) "LL" else "HQ")
                put("preserveFormants", module.preserveFormants)
            }
            is EffectModule.Formant -> JSONObject().apply {
                put("id", module.id)
                put("enabled", module.enabled)
                put("cents", module.cents)
                put("intelligibility", module.intelligibilityAssist)
            }
            is EffectModule.AutoTune -> JSONObject().apply {
                put("id", module.id)
                put("enabled", module.enabled)
                put("key", keyString(module.key))
                put("scale", scaleString(module.scale))
                put("retuneMs", module.retuneMs)
                put("humanize", module.humanizePercent)
                put("flexTune", module.flexTunePercent)
                put("formantPreserve", module.formantPreserve)
                put("snapStrength", module.snapStrengthPercent)
            }
            is EffectModule.Reverb -> JSONObject().apply {
                put("id", module.id)
                put("enabled", module.enabled)
                put("room", module.roomSize)
                put("damp", module.damping)
                put("predelayMs", module.preDelayMs)
                put("mix", module.mixPercent)
            }
            is EffectModule.Mix -> JSONObject().apply {
                put("id", module.id)
                put("enabled", module.enabled)
                put("wet", module.dryWetPercent)
                put("outGain", module.outputGainDb)
            }
        }.apply {
            // Fill in mix wet if missing on a mix-less preset structure.
            if (!has("wet") && module.id != "mix") {
                put("wet", presetWet)
            }
        }

    private fun latencyModeString(mode: LatencyMode): String =
        when (mode) {
            LatencyMode.LOW_LATENCY -> "LL"
            LatencyMode.BALANCED -> "Balanced"
            LatencyMode.HIGH_QUALITY -> "HQ"
        }

    private fun keyString(key: MusicalKey): String =
        when (key) {
            MusicalKey.C -> "C"
            MusicalKey.C_SHARP -> "C#"
            MusicalKey.D -> "D"
            MusicalKey.D_SHARP -> "D#"
            MusicalKey.E -> "E"
            MusicalKey.F -> "F"
            MusicalKey.F_SHARP -> "F#"
            MusicalKey.G -> "G"
            MusicalKey.G_SHARP -> "G#"
            MusicalKey.A -> "A"
            MusicalKey.A_SHARP -> "A#"
            MusicalKey.B -> "B"
        }

    private fun scaleString(scale: MusicalScale): String =
        when (scale) {
            MusicalScale.MAJOR -> "Major"
            MusicalScale.MINOR -> "Minor"
            MusicalScale.CHROMATIC -> "Chromatic"
            MusicalScale.DORIAN -> "Dorian"
            MusicalScale.PHRYGIAN -> "Phrygian"
            MusicalScale.LYDIAN -> "Lydian"
            MusicalScale.MIXOLYDIAN -> "Mixolydian"
            MusicalScale.AEOLIAN -> "Aeolian"
            MusicalScale.LOCRIAN -> "Locrian"
        }
}
