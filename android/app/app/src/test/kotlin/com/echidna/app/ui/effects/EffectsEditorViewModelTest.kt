package com.echidna.app.ui.effects

import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.Band
import com.echidna.app.model.EffectModule
import com.echidna.app.model.MusicalKey
import com.echidna.app.model.MusicalScale
import com.echidna.app.model.Preset
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Chain-editing contract for [EffectsEditorViewModel].
 *
 * The view model writes through the shared [ControlStateRepository] singleton, so each test runs
 * against a scratch preset it creates and deletes, restoring the previously active preset in
 * teardown. Without a bound [android.content.Context] the repository's persistence and
 * service-push paths are guarded no-ops, leaving the in-memory chain edits under test.
 *
 * `activePreset` is republished by a `combine(...).stateIn(Dispatchers.Default)` in the
 * repository, so every edit is bracketed by [settle] — the mutation itself is synchronous, but the
 * flow the editor reads back from catches up on another thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EffectsEditorViewModelTest {

    private val repo = ControlStateRepository
    private lateinit var originalPresetId: String
    private lateinit var scratchId: String
    private lateinit var vm: EffectsEditorViewModel

    @Before
    fun setUp() {
        originalPresetId = repo.activePreset.value.id
        scratchId = repo.createPreset("Effects Editor Scratch", null, null)
        repo.selectPreset(scratchId)
        vm = EffectsEditorViewModel()
        // Start from an empty chain so ordering assertions are about addModule, not the template.
        settle().modules.map { it.id }.forEach { id -> edit { removeModule(id) } }
        assertEquals(emptyList<String>(), chain())
    }

    @After
    fun tearDown() {
        repo.selectPreset(originalPresetId)
        repo.deletePreset(scratchId)
    }

    /** The repository's authoritative copy of the scratch preset. */
    private fun stored(): Preset = repo.presets.value.first { it.id == scratchId }

    /** Blocks until the editor's `activePreset` flow reflects the repository's stored copy. */
    private fun settle(): Preset {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (System.nanoTime() < deadline) {
            val visible = vm.activePreset.value
            if (visible == stored()) return visible
            Thread.sleep(2L)
        }
        fail("activePreset never caught up with the stored preset")
        error("unreachable")
    }

    /** Applies an editor action against a settled state and returns the settled result. */
    private fun edit(action: EffectsEditorViewModel.() -> Unit): Preset {
        settle()
        vm.action()
        return settle()
    }

    private fun chain(): List<String> = settle().modules.map { it.id }

    private inline fun <reified T : EffectModule> module(id: String): T =
        settle().modules.first { it.id == id } as T

    @Test
    fun `added stages are sorted into the native pipeline order regardless of insertion order`() {
        // Insert back-to-front; the persisted chain must still mirror the DSP graph.
        edit { addModule("mix") }
        edit { addModule("gate") }
        edit { addModule("autotune") }
        edit { addModule("eq") }

        assertEquals(listOf("gate", "eq", "autotune", "mix"), chain())
    }

    @Test
    fun `the full chain sorts to the canonical order`() {
        EffectsEditorViewModel.CANONICAL_ORDER.reversed().forEach { id -> edit { addModule(id) } }
        assertEquals(EffectsEditorViewModel.CANONICAL_ORDER, chain())
    }

    @Test
    fun `adding a stage twice is a no-op and preserves its edited parameters`() {
        edit { addModule("pitch") }
        edit { updatePitch(7f, 25f, EffectModule.PitchQuality.HQ, preserve = false) }

        edit { addModule("pitch") }

        assertEquals(listOf("pitch"), chain())
        val pitch = module<EffectModule.Pitch>("pitch")
        assertEquals(7f, pitch.semitones, 0f)
        assertEquals(25f, pitch.cents, 0f)
        assertEquals(EffectModule.PitchQuality.HQ, pitch.quality)
        assertFalse("re-adding must not reset the stage to defaults", pitch.preserveFormants)
    }

    @Test
    fun `an unknown stage id is rejected rather than added`() {
        edit { addModule("gate") }
        edit { addModule("not-a-real-stage") }
        assertEquals(listOf("gate"), chain())
    }

    @Test
    fun `removeModule drops only the named stage and is idempotent`() {
        edit { addModule("gate") }
        edit { addModule("comp") }
        edit { addModule("mix") }

        edit { removeModule("comp") }
        assertEquals(listOf("gate", "mix"), chain())

        edit { removeModule("comp") }
        assertEquals(listOf("gate", "mix"), chain())
    }

    @Test
    fun `toggleModule flips only the named stage`() {
        edit { addModule("gate") }
        edit { addModule("reverb") }
        assertTrue(module<EffectModule.Gate>("gate").enabled)

        edit { toggleModule("gate", false) }

        assertFalse(module<EffectModule.Gate>("gate").enabled)
        assertTrue("sibling stages must be untouched", module<EffectModule.Reverb>("reverb").enabled)
    }

    @Test
    fun `parameter updates are ignored for stages not in the chain`() {
        edit { addModule("gate") }
        val before = settle().modules

        edit { updateReverb(50f, 50f, 50f, 50f) }
        edit { updateCompressor(EffectModule.CompressorMode.AUTO, -10f, 9f, 1f, 1f, 1f, 1f) }

        assertEquals(before, settle().modules)
    }

    @Test
    fun `gate parameters round trip through the repository`() {
        edit { addModule("gate") }
        edit { updateGate(threshold = -62f, attack = 2.5f, release = 300f, hysteresis = 6f) }

        val gate = module<EffectModule.Gate>("gate")
        assertEquals(-62f, gate.thresholdDb, 0f)
        assertEquals(2.5f, gate.attackMs, 0f)
        assertEquals(300f, gate.releaseMs, 0f)
        assertEquals(6f, gate.hysteresisDb, 0f)
    }

    @Test
    fun `growing the equalizer appends bands and shrinking truncates from the end`() {
        edit { addModule("eq") }
        val original = module<EffectModule.Equalizer>("eq").bands
        assertEquals(5, original.size)

        edit { updateEqualizerBandCount(8) }
        val grown = module<EffectModule.Equalizer>("eq")
        assertEquals(8, grown.bandCount)
        assertEquals(8, grown.bands.size)
        assertEquals("existing bands must be preserved", original, grown.bands.take(5))
        assertEquals(listOf(100f, 200f, 300f), grown.bands.drop(5).map { it.frequency })
        assertTrue(grown.bands.drop(5).all { it.gainDb == 0f && it.q == 1f })

        edit { updateEqualizerBandCount(3) }
        val shrunk = module<EffectModule.Equalizer>("eq")
        assertEquals(3, shrunk.bandCount)
        assertEquals(original.take(3), shrunk.bands)
    }

    @Test
    fun `equalizer band edits apply in place and out-of-range indices are ignored`() {
        edit { addModule("eq") }
        edit { updateEqualizerBand(2, frequency = 2200f, gain = -4.5f, q = 2.5f) }

        val edited = module<EffectModule.Equalizer>("eq")
        assertEquals(Band(2200f, -4.5f, 2.5f), edited.bands[2])
        assertEquals(5, edited.bands.size)

        edit { updateEqualizerBand(99, frequency = 1f, gain = 1f, q = 1f) }
        edit { updateEqualizerBand(-1, frequency = 1f, gain = 1f, q = 1f) }
        assertEquals(
            "out-of-range edits must not alter the band list",
            edited.bands,
            module<EffectModule.Equalizer>("eq").bands,
        )
    }

    @Test
    fun `auto-tune musical settings round trip`() {
        edit { addModule("autotune") }
        edit {
            updateAutoTune(
                key = MusicalKey.F,
                scale = MusicalScale.MINOR,
                retune = 8f,
                humanize = 35f,
                flex = 15f,
                preserveFormant = false,
                snap = 60f,
            )
        }

        val tune = module<EffectModule.AutoTune>("autotune")
        assertEquals(MusicalKey.F, tune.key)
        assertEquals(MusicalScale.MINOR, tune.scale)
        assertEquals(8f, tune.retuneMs, 0f)
        assertEquals(35f, tune.humanizePercent, 0f)
        assertEquals(15f, tune.flexTunePercent, 0f)
        assertFalse(tune.formantPreserve)
        assertEquals(60f, tune.snapStrengthPercent, 0f)
    }

    @Test
    fun `warnings flow reflects edits made through the editor`() {
        edit { addModule("comp") }
        assertTrue(vm.warnings.value.none { it.message == "Compression ratio very high" })

        edit { updateCompressor(EffectModule.CompressorMode.MANUAL, -26f, 8f, 6f, 8f, 160f, 3f) }

        assertNotNull(
            "an extreme ratio must surface in the editor's warning feed",
            vm.warnings.value.firstOrNull { it.message == "Compression ratio very high" },
        )
    }

    @Test
    fun `newly added stages arrive enabled with finite defaults`() {
        EffectsEditorViewModel.CANONICAL_ORDER.forEach { id -> edit { addModule(id) } }

        val modules = settle().modules
        assertEquals(EffectsEditorViewModel.CANONICAL_ORDER.size, modules.size)
        assertTrue("every freshly added stage starts enabled", modules.all { it.enabled })
        val mix = modules.first { it.id == "mix" } as EffectModule.Mix
        assertTrue(mix.dryWetPercent.isFinite() && mix.outputGainDb.isFinite())
    }
}
