package com.echidna.app.data

import com.echidna.app.model.EffectModule
import com.echidna.app.model.LatencyMode
import com.echidna.app.model.MusicalKey
import com.echidna.app.model.MusicalScale
import com.echidna.app.model.Preset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [PresetSerializer] — the app<->engine preset JSON contract.
 *
 * Runs under Robolectric so the real `org.json` implementation is available on the
 * unit-test classpath (the stock JVM `android.jar` only ships stubbed org.json).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PresetSerializerTest {

    /** A preset exercising every module type, so the round-trip covers all serializers. */
    private fun fullPreset(): Preset = Preset(
        id = "fixed-id",
        name = "Round Trip",
        description = "covers every module",
        tags = setOf("NAT", "HQ"),
        latencyMode = LatencyMode.HIGH_QUALITY,
        dryWet = 70,
        modules = listOf(
            EffectModule.Gate(true, -48f, 6f, 110f, 3f),
            EffectModule.Equalizer(
                enabled = true,
                bands = listOf(
                    com.echidna.app.model.Band(120f, 2f, 0.7f),
                    com.echidna.app.model.Band(3500f, -2.5f, 2.0f)
                ),
                bandCount = 5
            ),
            EffectModule.Compressor(true, EffectModule.CompressorMode.AUTO, -26f, 3.5f, 6f, 5f, 120f, 4f),
            EffectModule.Pitch(true, 2.5f, 10f, EffectModule.PitchQuality.HQ, preserveFormants = true),
            EffectModule.Formant(true, -180f, intelligibilityAssist = true),
            EffectModule.AutoTune(true, MusicalKey.F_SHARP, MusicalScale.DORIAN, 15f, 20f, 30f, true, 80f),
            EffectModule.Reverb(true, 12f, 18f, 8f, 15f),
            EffectModule.Mix(true, 70f, -1.5f)
        )
    )

    @Test
    fun `toJson emits the documented schema keys`() {
        val root = JSONObject(PresetSerializer.toJson(fullPreset()))
        assertEquals("Round Trip", root.getString("name"))
        assertEquals(1, root.getInt("version"))
        val meta = root.getJSONObject("meta")
        assertEquals("covers every module", meta.getString("description"))
        assertTrue(meta.getJSONArray("tags").length() == 2)
        val engine = root.getJSONObject("engine")
        assertEquals("HQ", engine.getString("latencyMode"))
        assertEquals(LatencyMode.HIGH_QUALITY.targetMs, engine.getInt("blockMs"))
        val modules = root.getJSONArray("modules")
        assertTrue(modules.length() >= 8)
        // Every module object carries an id + enabled flag.
        for (i in 0 until modules.length()) {
            val m = modules.getJSONObject(i)
            assertTrue("module $i missing id", m.has("id"))
            assertTrue("module $i missing enabled", m.has("enabled"))
        }
    }

    @Test
    fun `round trip preserves content across every module type`() {
        val original = fullPreset()
        val restored = PresetSerializer.fromJson(PresetSerializer.toJson(original))
        assertNotNull(restored)
        restored!!
        // id is intentionally regenerated on import; everything else must survive.
        assertEquals(original.name, restored.name)
        assertEquals(original.description, restored.description)
        assertEquals(original.tags, restored.tags)
        assertEquals(original.latencyMode, restored.latencyMode)
        assertEquals(original.dryWet, restored.dryWet)

        val g = restored.modules.filterIsInstance<EffectModule.Gate>().single()
        assertEquals(-48f, g.thresholdDb, 1e-3f)
        assertEquals(110f, g.releaseMs, 1e-3f)

        val eq = restored.modules.filterIsInstance<EffectModule.Equalizer>().single()
        assertEquals(2, eq.bands.size)
        assertEquals(120f, eq.bands[0].frequency, 1e-3f)
        assertEquals(-2.5f, eq.bands[1].gainDb, 1e-3f)

        val comp = restored.modules.filterIsInstance<EffectModule.Compressor>().single()
        assertEquals(EffectModule.CompressorMode.AUTO, comp.mode)
        assertEquals(3.5f, comp.ratio, 1e-3f)

        val pitch = restored.modules.filterIsInstance<EffectModule.Pitch>().single()
        assertEquals(2.5f, pitch.semitones, 1e-3f)
        assertEquals(10f, pitch.cents, 1e-3f)
        assertEquals(EffectModule.PitchQuality.HQ, pitch.quality)

        val autotune = restored.modules.filterIsInstance<EffectModule.AutoTune>().single()
        assertEquals(MusicalKey.F_SHARP, autotune.key)
        assertEquals(MusicalScale.DORIAN, autotune.scale)

        val reverb = restored.modules.filterIsInstance<EffectModule.Reverb>().single()
        assertEquals(12f, reverb.roomSize, 1e-3f)

        val mix = restored.modules.filterIsInstance<EffectModule.Mix>().single()
        assertEquals(70f, mix.dryWetPercent, 1e-3f)
        assertEquals(-1.5f, mix.outputGainDb, 1e-3f)
    }

    @Test
    fun `latencyModeString maps every mode`() {
        assertEquals("LL", PresetSerializer.latencyModeString(LatencyMode.LOW_LATENCY))
        assertEquals("Balanced", PresetSerializer.latencyModeString(LatencyMode.BALANCED))
        assertEquals("HQ", PresetSerializer.latencyModeString(LatencyMode.HIGH_QUALITY))
    }

    @Test
    fun `fromJson rejects malformed or empty payloads`() {
        assertNull("non-JSON must be rejected", PresetSerializer.fromJson("not json"))
        assertNull("blank name must be rejected", PresetSerializer.fromJson("""{"name":"","modules":[{"id":"mix","wet":50}]}"""))
        assertNull("missing name must be rejected", PresetSerializer.fromJson("""{"modules":[{"id":"mix","wet":50}]}"""))
        assertNull("missing modules must be rejected", PresetSerializer.fromJson("""{"name":"X"}"""))
        assertNull("no recognised modules must be rejected", PresetSerializer.fromJson("""{"name":"X","modules":[{"id":"bogus"}]}"""))
    }

    @Test
    fun `fromJson accepts a minimal valid preset and defaults latency to Balanced`() {
        val preset = PresetSerializer.fromJson("""{"name":"Min","modules":[{"id":"mix","wet":40}]}""")
        assertNotNull(preset)
        assertEquals("Min", preset!!.name)
        assertEquals(LatencyMode.BALANCED, preset.latencyMode)
        assertEquals(40, preset.dryWet)
    }
}
