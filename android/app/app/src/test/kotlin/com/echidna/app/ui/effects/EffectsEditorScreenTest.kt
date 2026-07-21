package com.echidna.app.ui.effects

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.data.DismissedAlertsStore
import com.echidna.app.model.EffectModule
import com.echidna.app.model.Preset
import com.echidna.app.ui.components.AlertTestTags
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose coverage for [EffectsEditorScreen].
 *
 * [EffectsEditorViewModelTest] pins the chain algebra; this class pins what the *screen* does with
 * it: the chain renders in the native DSP order, the add section flips between "here is what you
 * can add" and "everything is already in the chain", expanding a card reveals that stage's own
 * controls, and a control edit lands on the field it is labelled with (and on no other).
 *
 * Every edit goes through the shared repository singleton, whose `activePreset` is republished on
 * `Dispatchers.Default`, so mutations are bracketed with the same bounded [settle] the view-model
 * test uses. Each test runs against a scratch preset that teardown removes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h1200dp")
class EffectsEditorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val repo = ControlStateRepository
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var originalPresetId: String
    private lateinit var scratchId: String
    private lateinit var vm: EffectsEditorViewModel

    @Before
    fun setUp() {
        context.getSharedPreferences(DismissedAlertsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        originalPresetId = repo.activePreset.value.id
        scratchId = repo.createPreset("Editor Scratch", null, null)
        repo.selectPreset(scratchId)
        vm = EffectsEditorViewModel()
        // Start from an empty chain so what is on screen is exactly what the test added.
        settle().modules.map { it.id }.forEach { id -> edit { removeModule(id) } }
        assertEquals(emptyList<String>(), chain())
    }

    @After
    fun tearDown() {
        repo.selectPreset(originalPresetId)
        repo.deletePreset(scratchId)
    }

    private fun setContent() {
        composeRule.setContent { MaterialTheme { EffectsEditorScreen(viewModel = vm) } }
    }

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

    private fun edit(action: EffectsEditorViewModel.() -> Unit): Preset {
        settle()
        vm.action()
        return settle()
    }

    private fun chain(): List<String> = settle().modules.map { it.id }

    private inline fun <reified T : EffectModule> module(id: String): T =
        settle().modules.first { it.id == id } as T

    /** Settles the repository flow and then lets Compose recompose against it. */
    private fun settleUi() {
        settle()
        composeRule.waitForIdle()
    }

    /** Matches a Slider (any node exposing the SetProgress action). */
    private val isSlider = SemanticsMatcher("is a slider") { node ->
        node.config.contains(SemanticsActions.SetProgress)
    }

    private fun scrollTo(matcher: SemanticsMatcher) =
        composeRule.onNode(hasScrollAction()).performScrollToNode(matcher)

    /** Taps a node's OnClick semantics; Robolectric has no real gesture pipeline for lazy items. */
    private fun click(text: String) {
        scrollTo(hasText(text))
        composeRule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        settleUi()
    }

    private fun topOf(text: String): Float =
        composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    // --- Header + add section -------------------------------------------------------------------

    @Test
    fun `the header names the preset whose chain is being edited`() {
        setContent()
        composeRule.onNodeWithText("Effects Chain").assertExists()
        composeRule.onNodeWithText("Editing preset: Editor Scratch").assertExists()
    }

    @Test
    fun `an empty chain offers every canonical stage to add`() {
        setContent()
        scrollTo(hasText("Add effect"))
        composeRule.onNodeWithText("Stages are inserted at their position in the signal path.")
            .assertExists()
        composeRule.onNodeWithText("Every effect is already in the chain.").assertDoesNotExist()
        listOf(
            "Noise Gate", "Equalizer", "Compressor / AGC", "Pitch Shift",
            "Formant", "Auto-Tune", "Reverb", "Dry/Wet Mix",
        ).forEach { composeRule.onNodeWithText(it).assertExists() }
    }

    @Test
    fun `a complete chain reports that nothing is left to add`() {
        EffectsEditorViewModel.CANONICAL_ORDER.forEach { id -> edit { addModule(id) } }
        setContent()

        scrollTo(hasText("Every effect is already in the chain."))
        composeRule.onNodeWithText("Every effect is already in the chain.").assertExists()
        composeRule.onNodeWithText("Stages are inserted at their position in the signal path.")
            .assertDoesNotExist()
    }

    @Test
    fun `tapping an add chip inserts exactly that stage`() {
        setContent()
        click("Reverb")

        assertEquals(listOf("reverb"), chain())
        // It now renders as a chain card with its one-line description, not as an add chip.
        composeRule.onNodeWithText("Adds room ambience and echo").assertExists()
    }

    @Test
    fun `the chain renders in native DSP order regardless of insertion order`() {
        // Inserted back-to-front: mix is last in the pipeline, gate is first.
        edit { addModule("mix") }
        edit { addModule("gate") }
        setContent()

        assertTrue(
            "the gate card must render above the mix card",
            topOf("Noise Gate") < topOf("Dry/Wet Mix"),
        )
        // Stage badges number the pipeline position, not the insertion order.
        composeRule.onNodeWithText("1").assertExists()
        composeRule.onNodeWithText("2").assertExists()
    }

    // --- Expand / collapse ----------------------------------------------------------------------

    @Test
    fun `expanding a card reveals that stage's controls and collapsing hides them again`() {
        edit { addModule("gate") }
        setContent()

        // Collapsed: only the one-line description is shown.
        composeRule.onNodeWithText("Mutes quiet background noise").assertExists()
        composeRule.onNodeWithText("Remove from chain").assertDoesNotExist()
        composeRule.onNodeWithText("Threshold: -50 dB").assertDoesNotExist()

        click("Noise Gate")
        composeRule.onNodeWithText("Threshold: -50 dB").assertExists()
        composeRule.onNodeWithText("Hysteresis: 3 dB").assertExists()
        composeRule
            .onNodeWithText("Silences audio below a set level", substring = true)
            .assertExists()
        composeRule.onNodeWithText("Remove from chain").assertExists()

        click("Noise Gate")
        composeRule.onNodeWithText("Remove from chain").assertDoesNotExist()
        composeRule.onNodeWithText("Threshold: -50 dB").assertDoesNotExist()
    }

    @Test
    fun `only one stage is expanded at a time`() {
        edit { addModule("gate") }
        edit { addModule("reverb") }
        setContent()

        click("Noise Gate")
        composeRule.onNodeWithText("Hysteresis: 3 dB").assertExists()

        click("Reverb")
        composeRule.onNodeWithText("Room: 12").assertExists()
        composeRule.onNodeWithText("Hysteresis: 3 dB").assertDoesNotExist()
        composeRule.onAllNodesWithText("Remove from chain").assertCountEquals(1)
    }

    // --- Enable / remove ------------------------------------------------------------------------

    @Test
    fun `the enable switch bypasses only its own stage`() {
        edit { addModule("gate") }
        edit { addModule("reverb") }
        setContent()

        val gateSwitch = composeRule.onNode(isToggleable() and hasAnyAncestor(hasText("Noise Gate")))
        gateSwitch.assertIsOn()
        gateSwitch.performSemanticsAction(SemanticsActions.OnClick)
        settleUi()

        assertFalse(module<EffectModule.Gate>("gate").enabled)
        assertTrue("sibling stages must be untouched", module<EffectModule.Reverb>("reverb").enabled)
        composeRule.onNode(isToggleable() and hasAnyAncestor(hasText("Noise Gate"))).assertIsOff()
        composeRule.onNode(isToggleable() and hasAnyAncestor(hasText("Reverb"))).assertIsOn()
    }

    @Test
    fun `remove from chain deletes the stage and returns it to the add section`() {
        edit { addModule("gate") }
        edit { addModule("reverb") }
        setContent()

        click("Noise Gate")
        click("Remove from chain")

        assertEquals(listOf("reverb"), chain())
        // Back as an offer, not as a card: its chain-card description is gone.
        composeRule.onNodeWithText("Mutes quiet background noise").assertDoesNotExist()
        scrollTo(hasText("Add effect"))
        composeRule.onNodeWithText("Noise Gate").assertExists()
    }

    // --- Parameter editing ----------------------------------------------------------------------

    @Test
    fun `a slider edit writes the labelled field and leaves its neighbours alone`() {
        edit { addModule("gate") }
        setContent()
        click("Noise Gate")

        val before = module<EffectModule.Gate>("gate")
        // Gate controls compose as Threshold, Attack, Release, Hysteresis.
        composeRule.onAllNodes(isSlider)[0]
            .performSemanticsAction(SemanticsActions.SetProgress) { it(-70f) }
        settleUi()

        val after = module<EffectModule.Gate>("gate")
        assertEquals(-70f, after.thresholdDb, 0.001f)
        assertEquals("attack must not move", before.attackMs, after.attackMs, 0f)
        assertEquals("release must not move", before.releaseMs, after.releaseMs, 0f)
        assertEquals("hysteresis must not move", before.hysteresisDb, after.hysteresisDb, 0f)
        composeRule.onNodeWithText("Threshold: -70 dB").assertExists()
    }

    @Test
    fun `a switch inside a stage's controls edits that stage's boolean field`() {
        edit { addModule("pitch") }
        setContent()
        click("Pitch Shift")

        assertTrue(module<EffectModule.Pitch>("pitch").preserveFormants)
        // Two toggleables are on screen: the card's enable switch and Preserve Formants.
        // The card's own enable switch sits inside the merged header (which carries the stage
        // title); the parameter switch is the toggleable that does not.
        composeRule.onNode(isToggleable() and !hasAnyAncestor(hasText("Pitch Shift")))
            .performSemanticsAction(SemanticsActions.OnClick)
        settleUi()

        assertFalse(module<EffectModule.Pitch>("pitch").preserveFormants)
        assertTrue("the stage itself must stay enabled", module<EffectModule.Pitch>("pitch").enabled)
    }

    @Test
    fun `the equalizer band-count buttons resize the band list`() {
        edit { addModule("eq") }
        setContent()
        click("Equalizer")

        composeRule.onNodeWithText("Bands: 5").assertExists()
        composeRule.onNodeWithText("Band 5").assertExists()

        click("3")
        assertEquals(3, module<EffectModule.Equalizer>("eq").bandCount)
        composeRule.onNodeWithText("Bands: 3").assertExists()
        composeRule.onNodeWithText("Band 5").assertDoesNotExist()
        composeRule.onNodeWithText("Band 3").assertExists()
    }

    @Test
    fun `the compressor mode buttons switch modes and the current mode is shown`() {
        edit { addModule("comp") }
        setContent()
        click("Compressor / AGC")

        assertEquals(EffectModule.CompressorMode.MANUAL, module<EffectModule.Compressor>("comp").mode)
        composeRule.onNodeWithText("MANUAL").assertExists()

        click("Auto")
        assertEquals(EffectModule.CompressorMode.AUTO, module<EffectModule.Compressor>("comp").mode)
        composeRule.onNodeWithText("AUTO").assertExists()
        composeRule.onNodeWithText("MANUAL").assertDoesNotExist()
    }

    @Test
    fun `every stage exposes its own labelled controls when expanded`() {
        EffectsEditorViewModel.CANONICAL_ORDER.forEach { id -> edit { addModule(id) } }
        setContent()

        val expectations = listOf(
            "Noise Gate" to "Hysteresis: 3 dB",
            "Equalizer" to "Bands: 5",
            "Compressor / AGC" to "Makeup: 3 dB",
            "Pitch Shift" to "Quality: LL",
            "Formant" to "Intelligibility Assist",
            "Auto-Tune" to "Snap Strength: 80 %",
            "Reverb" to "Pre-Delay: 5 ms",
            "Dry/Wet Mix" to "Output Gain: 0 dB",
        )
        expectations.forEach { (stage, control) ->
            click(stage)
            scrollTo(hasText(control))
            composeRule.onNodeWithText(control).assertExists()
            click(stage)
        }
    }

    // --- Preset warnings ------------------------------------------------------------------------

    @Test
    fun `a live preset warning renders as a dismissible alert above the chain`() {
        edit { addModule("comp") }
        edit {
            updateCompressor(EffectModule.CompressorMode.MANUAL, -26f, 8f, 6f, 8f, 160f, 3f)
        }
        assertTrue(
            "precondition: the extreme ratio must raise a warning",
            vm.warnings.value.any { it.message == "Compression ratio very high" },
        )
        setContent()

        composeRule.onNodeWithTag(AlertTestTags.CARD).assertExists()
        composeRule.onNodeWithText("Preset warning").assertExists()
        composeRule.onNodeWithText("Compression ratio very high").assertExists()
    }

    @Test
    fun `no warning means no alert card`() {
        edit { addModule("comp") }
        assertTrue(
            "precondition: the default compressor is not extreme",
            vm.warnings.value.none { it.message == "Compression ratio very high" },
        )
        setContent()

        composeRule.onNodeWithText("Preset warning").assertDoesNotExist()
    }
}
