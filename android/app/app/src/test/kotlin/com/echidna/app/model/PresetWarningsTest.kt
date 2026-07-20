package com.echidna.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Threshold contract for [PresetValidator]. Every rule is pinned at both sides of its boundary so
 * a shifted comparison (`<=` -> `<`, or a moved constant) fails the suite rather than silently
 * changing which presets the editor flags.
 */
class PresetWarningsTest {

    private fun preset(vararg modules: EffectModule, dryWet: Int = 50) = Preset(
        id = "warnings",
        name = "Warnings",
        description = null,
        tags = emptySet(),
        latencyMode = LatencyMode.LOW_LATENCY,
        dryWet = dryWet,
        modules = modules.toList(),
    )

    private fun messages(preset: Preset): List<String> =
        PresetValidator.evaluate(preset).map { it.message }

    private fun gate(threshold: Float = -50f, attack: Float = 5f, release: Float = 120f) =
        EffectModule.Gate(true, threshold, attack, release, 3f)

    private fun compressor(threshold: Float = -26f, ratio: Float = 3f) =
        EffectModule.Compressor(true, EffectModule.CompressorMode.MANUAL, threshold, ratio, 6f, 8f, 160f, 3f)

    private fun autoTune(retune: Float = 20f, humanize: Float = 20f) =
        EffectModule.AutoTune(true, MusicalKey.C, MusicalScale.MAJOR, retune, humanize, 0f, true, 80f)

    @Test
    fun `a moderate preset produces no warnings`() {
        val warnings = PresetValidator.evaluate(
            preset(gate(), compressor(), EffectModule.Reverb(true, 12f, 20f, 5f, 12f))
        )
        assertEquals(emptyList<PresetWarning>(), warnings)
    }

    @Test
    fun `gate threshold warns only outside the open interval`() {
        assertTrue(messages(preset(gate(threshold = -78f))).contains("Gate threshold near extreme"))
        assertTrue(messages(preset(gate(threshold = -22f))).contains("Gate threshold near extreme"))
        assertTrue(messages(preset(gate(threshold = -77.9f))).isEmpty())
        assertTrue(messages(preset(gate(threshold = -22.1f))).isEmpty())
    }

    @Test
    fun `gate attack and release are informational at their bounds`() {
        val fast = PresetValidator.evaluate(preset(gate(attack = 1f)))
        assertEquals(listOf("Gate attack extremely fast"), fast.map { it.message })
        assertEquals(WarningSeverity.INFO, fast.single().severity)
        assertTrue(messages(preset(gate(attack = 1.1f))).isEmpty())

        assertEquals(
            listOf("Gate release very slow"),
            messages(preset(gate(release = 450f))),
        )
        assertTrue(messages(preset(gate(release = 449.9f))).isEmpty())
    }

    @Test
    fun `compressor threshold warns and extreme ratio is critical`() {
        assertTrue(messages(preset(compressor(threshold = -55f))).contains("Compressor threshold extreme"))
        assertTrue(messages(preset(compressor(threshold = -6f))).contains("Compressor threshold extreme"))
        assertTrue(messages(preset(compressor(threshold = -54.9f))).isEmpty())

        val hot = PresetValidator.evaluate(preset(compressor(ratio = 5.5f)))
        assertEquals(listOf("Compression ratio very high"), hot.map { it.message })
        assertEquals(WarningSeverity.CRITICAL, hot.single().severity)
        assertTrue(messages(preset(compressor(ratio = 5.4f))).isEmpty())
    }

    @Test
    fun `pitch shift near the limit is critical in both directions`() {
        val quality = EffectModule.PitchQuality.LL
        val up = PresetValidator.evaluate(
            preset(EffectModule.Pitch(true, 10f, 0f, quality, true))
        )
        val down = PresetValidator.evaluate(
            preset(EffectModule.Pitch(true, -10f, 0f, quality, true))
        )
        assertEquals(WarningSeverity.CRITICAL, up.single().severity)
        assertEquals(WarningSeverity.CRITICAL, down.single().severity)
        assertTrue(
            messages(preset(EffectModule.Pitch(true, 9.9f, 0f, quality, true))).isEmpty()
        )
    }

    @Test
    fun `formant shift warns beyond the intelligibility band`() {
        assertTrue(
            messages(preset(EffectModule.Formant(true, 550f, true)))
                .contains("Formant shift may sound unnatural")
        )
        assertTrue(
            messages(preset(EffectModule.Formant(true, -550f, true)))
                .contains("Formant shift may sound unnatural")
        )
        assertTrue(messages(preset(EffectModule.Formant(true, 549f, true))).isEmpty())
    }

    @Test
    fun `auto-tune robotic and minimal-humanize hints stack independently`() {
        assertEquals(
            listOf("Auto-Tune retune speed is robotic"),
            messages(preset(autoTune(retune = 5f))),
        )
        assertEquals(
            listOf("Auto-Tune humanize is minimal"),
            messages(preset(autoTune(humanize = 5f))),
        )
        assertEquals(
            listOf("Auto-Tune retune speed is robotic", "Auto-Tune humanize is minimal"),
            messages(preset(autoTune(retune = 1f, humanize = 0f))),
        )
    }

    @Test
    fun `wet reverb and hot output gain warn at their thresholds`() {
        assertEquals(
            listOf("Reverb mix is very wet"),
            messages(preset(EffectModule.Reverb(true, 12f, 20f, 5f, 48f))),
        )
        assertTrue(messages(preset(EffectModule.Reverb(true, 12f, 20f, 5f, 47.9f))).isEmpty())

        assertEquals(
            listOf("Output gain near clipping"),
            messages(preset(EffectModule.Mix(true, 60f, 11f))),
        )
        assertTrue(messages(preset(EffectModule.Mix(true, 60f, 10.9f))).isEmpty())
    }

    @Test
    fun `dry-wet warning is preset-level and independent of the module chain`() {
        assertEquals(
            listOf("Dry/wet mix heavily favors wet signal"),
            messages(preset(dryWet = 95)),
        )
        assertTrue(messages(preset(dryWet = 94)).isEmpty())
    }

    @Test
    fun `warnings from every stage accumulate in chain order`() {
        val warnings = PresetValidator.evaluate(
            preset(
                gate(threshold = -80f, attack = 0.5f),
                compressor(ratio = 8f),
                EffectModule.Mix(true, 100f, 12f),
                dryWet = 100,
            )
        )
        assertEquals(
            listOf(
                "Gate threshold near extreme",
                "Gate attack extremely fast",
                "Compression ratio very high",
                "Output gain near clipping",
                "Dry/wet mix heavily favors wet signal",
            ),
            warnings.map { it.message },
        )
    }

    @Test
    fun `disabled stages are still validated`() {
        // The validator inspects parameters, not the enabled flag: a bypassed-but-extreme stage
        // must still be reported so re-enabling it is not a surprise.
        val warnings = PresetValidator.evaluate(
            preset(EffectModule.Gate(false, -80f, 5f, 120f, 3f))
        )
        assertEquals(listOf("Gate threshold near extreme"), warnings.map { it.message })
    }

    @Test
    fun `equalizer bands carry no warnings of their own`() {
        val eq = EffectModule.Equalizer(true, listOf(Band(1000f, 24f, 0.1f)), 1)
        assertEquals(emptyList<PresetWarning>(), PresetValidator.evaluate(preset(eq)))
    }
}
