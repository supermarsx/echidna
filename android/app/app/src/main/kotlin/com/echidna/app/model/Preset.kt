package com.echidna.app.model

data class Preset(
    val id: String,
    val name: String,
    val description: String?,
    val tags: Set<String>,
    val latencyMode: LatencyMode,
    val dryWet: Int,
    val modules: List<EffectModule>
)

data class Band(
    val frequency: Float,
    val gainDb: Float,
    val q: Float
)

sealed class EffectModule(open val enabled: Boolean, open val id: String) {
    data class Gate(
        override val enabled: Boolean,
        val thresholdDb: Float,
        val attackMs: Float,
        val releaseMs: Float,
        val hysteresisDb: Float
    ) : EffectModule(enabled, "gate")

    data class Equalizer(
        override val enabled: Boolean,
        val bands: List<Band>,
        val bandCount: Int
    ) : EffectModule(enabled, "eq")

    data class Compressor(
        override val enabled: Boolean,
        val mode: CompressorMode,
        val thresholdDb: Float,
        val ratio: Float,
        val kneeDb: Float,
        val attackMs: Float,
        val releaseMs: Float,
        val makeupGainDb: Float
    ) : EffectModule(enabled, "comp")

    enum class CompressorMode { MANUAL, AUTO }

    data class Pitch(
        override val enabled: Boolean,
        val semitones: Float,
        val cents: Float,
        val quality: PitchQuality,
        val preserveFormants: Boolean
    ) : EffectModule(enabled, "pitch")

    enum class PitchQuality { LL, HQ }

    data class Formant(
        override val enabled: Boolean,
        val cents: Float,
        val intelligibilityAssist: Boolean
    ) : EffectModule(enabled, "formant")

    data class AutoTune(
        override val enabled: Boolean,
        val key: MusicalKey,
        val scale: MusicalScale,
        val retuneMs: Float,
        val humanizePercent: Float,
        val flexTunePercent: Float,
        val formantPreserve: Boolean,
        val snapStrengthPercent: Float
    ) : EffectModule(enabled, "autotune")

    data class Reverb(
        override val enabled: Boolean,
        val roomSize: Float,
        val damping: Float,
        val preDelayMs: Float,
        val mixPercent: Float
    ) : EffectModule(enabled, "reverb")

    data class Mix(
        override val enabled: Boolean,
        val dryWetPercent: Float,
        val outputGainDb: Float
    ) : EffectModule(enabled, "mix")
}

enum class MusicalKey(val displayName: String) {
    C("C"), C_SHARP("C♯/D♭"), D("D"), D_SHARP("D♯/E♭"), E("E"), F("F"),
    F_SHARP("F♯/G♭"), G("G"), G_SHARP("G♯/A♭"), A("A"), A_SHARP("A♯/B♭"), B("B");
}

enum class MusicalScale {
    MAJOR, MINOR, CHROMATIC, DORIAN, PHRYGIAN, LYDIAN, MIXOLYDIAN, AEOLIAN, LOCRIAN
}
