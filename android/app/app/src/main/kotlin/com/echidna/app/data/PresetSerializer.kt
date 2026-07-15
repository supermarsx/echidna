package com.echidna.app.data

import com.echidna.app.model.Band
import com.echidna.app.model.EffectModule
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.MusicalKey
import com.echidna.app.model.MusicalScale
import com.echidna.app.model.Preset
import org.json.JSONArray
import org.json.JSONObject

object PresetSerializer {
    fun fromJson(json: String): Preset? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val name = root.optString("name").ifBlank { return null }
        val meta = root.optJSONObject("meta")
        val description = meta?.optString("description")?.ifBlank { null }
        val tagsArray = meta?.optJSONArray("tags")
        val tags = mutableSetOf<String>()
        if (tagsArray != null) {
            for (i in 0 until tagsArray.length()) {
                tagsArray.optString(i)?.let { tags.add(it) }
            }
        }
        val engineObj = root.optJSONObject("engine")
        val latencyMode = parseLatencyMode(engineObj?.optString("latencyMode"))
        val modulesArray = root.optJSONArray("modules") ?: return null
        val modules = mutableListOf<EffectModule>()
        var dryWet = 50
        for (i in 0 until modulesArray.length()) {
            val obj = modulesArray.optJSONObject(i) ?: continue
            val module = deserializeModule(obj)
            if (module != null) {
                modules.add(module)
                if (module is EffectModule.Mix) {
                    dryWet = module.dryWetPercent.toInt()
                }
            }
        }
        if (modules.isEmpty()) return null
        return Preset(
            // Version-0 exports did not carry an id. Generate one only for that legacy shape;
            // version-1 persistence must preserve ids so active/default/app bindings survive restart.
            id = root.optString("id")
                .takeIf { it.isNotBlank() && it.length <= 128 }
                ?: java.util.UUID.randomUUID().toString(),
            name = name,
            description = description,
            tags = tags,
            latencyMode = latencyMode,
            dryWet = dryWet,
            modules = modules
        )
    }

    fun toJson(preset: Preset): String {
        val root = JSONObject()
        root.put("id", preset.id)
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

    fun latencyModeString(mode: LatencyMode): String =
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

    private fun parseLatencyMode(value: String?): LatencyMode =
        when (value) {
            "LL" -> LatencyMode.LOW_LATENCY
            "HQ" -> LatencyMode.HIGH_QUALITY
            else -> LatencyMode.BALANCED
        }

    private fun parseKey(value: String?): MusicalKey =
        when (value) {
            "C#" -> MusicalKey.C_SHARP
            "D" -> MusicalKey.D
            "D#" -> MusicalKey.D_SHARP
            "E" -> MusicalKey.E
            "F" -> MusicalKey.F
            "F#" -> MusicalKey.F_SHARP
            "G" -> MusicalKey.G
            "G#" -> MusicalKey.G_SHARP
            "A" -> MusicalKey.A
            "A#" -> MusicalKey.A_SHARP
            "B" -> MusicalKey.B
            else -> MusicalKey.C
        }

    private fun parseScale(value: String?): MusicalScale =
        when (value) {
            "Minor" -> MusicalScale.MINOR
            "Chromatic" -> MusicalScale.CHROMATIC
            "Dorian" -> MusicalScale.DORIAN
            "Phrygian" -> MusicalScale.PHRYGIAN
            "Lydian" -> MusicalScale.LYDIAN
            "Mixolydian" -> MusicalScale.MIXOLYDIAN
            "Aeolian" -> MusicalScale.AEOLIAN
            "Locrian" -> MusicalScale.LOCRIAN
            else -> MusicalScale.MAJOR
        }

    private fun deserializeModule(obj: JSONObject): EffectModule? {
        val id = obj.optString("id").ifBlank { return null }
        val enabled = obj.optBoolean("enabled", true)
        return when (id) {
            "gate" -> EffectModule.Gate(
                enabled = enabled,
                thresholdDb = obj.optDouble("threshold", -50.0).toFloat(),
                attackMs = obj.optDouble("attackMs", 5.0).toFloat(),
                releaseMs = obj.optDouble("releaseMs", 120.0).toFloat(),
                hysteresisDb = obj.optDouble("hysteresis", 3.0).toFloat()
            )
            "eq" -> {
                val bandsArray = obj.optJSONArray("bands")
                val bands = mutableListOf<Band>()
                if (bandsArray != null) {
                    for (i in 0 until bandsArray.length()) {
                        val b = bandsArray.optJSONObject(i) ?: continue
                        bands.add(Band(
                            frequency = b.optDouble("f", 1000.0).toFloat(),
                            gainDb = b.optDouble("g", 0.0).toFloat(),
                            q = b.optDouble("q", 1.0).toFloat()
                        ))
                    }
                }
                EffectModule.Equalizer(enabled, bands, obj.optInt("bandCount", 5).coerceAtLeast(bands.size))
            }
            "comp" -> EffectModule.Compressor(
                enabled = enabled,
                mode = if (obj.optString("mode") == "auto") EffectModule.CompressorMode.AUTO
                       else EffectModule.CompressorMode.MANUAL,
                thresholdDb = obj.optDouble("threshold", -30.0).toFloat(),
                ratio = obj.optDouble("ratio", 3.0).toFloat(),
                kneeDb = obj.optDouble("knee", 6.0).toFloat(),
                attackMs = obj.optDouble("attackMs", 8.0).toFloat(),
                releaseMs = obj.optDouble("releaseMs", 160.0).toFloat(),
                makeupGainDb = obj.optDouble("makeup", 0.0).toFloat()
            )
            "pitch" -> EffectModule.Pitch(
                enabled = enabled,
                semitones = obj.optDouble("semitones", 0.0).toFloat(),
                cents = obj.optDouble("cents", 0.0).toFloat(),
                quality = if (obj.optString("quality") == "HQ") EffectModule.PitchQuality.HQ
                          else EffectModule.PitchQuality.LL,
                preserveFormants = obj.optBoolean("preserveFormants", true)
            )
            "formant" -> EffectModule.Formant(
                enabled = enabled,
                cents = obj.optDouble("cents", 0.0).toFloat(),
                intelligibilityAssist = obj.optBoolean("intelligibility", false)
            )
            "autotune" -> EffectModule.AutoTune(
                enabled = enabled,
                key = parseKey(obj.optString("key")),
                scale = parseScale(obj.optString("scale")),
                retuneMs = obj.optDouble("retuneMs", 120.0).toFloat(),
                humanizePercent = obj.optDouble("humanize", 50.0).toFloat(),
                flexTunePercent = obj.optDouble("flexTune", 30.0).toFloat(),
                formantPreserve = obj.optBoolean("formantPreserve", true),
                snapStrengthPercent = obj.optDouble("snapStrength", 50.0).toFloat()
            )
            "reverb" -> EffectModule.Reverb(
                enabled = enabled,
                roomSize = obj.optDouble("room", 10.0).toFloat(),
                damping = obj.optDouble("damp", 10.0).toFloat(),
                preDelayMs = obj.optDouble("predelayMs", 0.0).toFloat(),
                mixPercent = obj.optDouble("mix", 5.0).toFloat()
            )
            "mix" -> EffectModule.Mix(
                enabled = enabled,
                dryWetPercent = obj.optDouble("wet", 50.0).toFloat(),
                outputGainDb = obj.optDouble("outGain", 0.0).toFloat()
            )
            else -> null
        }
    }
}
