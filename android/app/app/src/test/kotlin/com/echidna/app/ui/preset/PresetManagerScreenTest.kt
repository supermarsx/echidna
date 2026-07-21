package com.echidna.app.ui.preset

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.EffectModule
import com.echidna.app.model.LatencyMode
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose coverage for [PresetManagerScreen].
 *
 * The screen's job is to make preset state legible and its destructive actions hard to trigger by
 * accident, so this class pins: the active preset is pinned to the top and cannot be re-activated
 * or deleted, search narrows the list (and says so honestly when nothing matches), delete goes
 * through a confirmation, and the import/export/share hand-offs pass real payloads back to the
 * caller.
 *
 * Presets live in the process-wide [ControlStateRepository]; every test filters the list down to a
 * uniquely-named scratch preset so its assertions are about one card, and teardown deletes them.
 *
 * NOT covered here, deliberately: the create and rename dialogs. Both are an `AlertDialog`
 * containing an `OutlinedTextField`, and under Robolectric no interaction is possible while one is
 * open — `waitForIdle` never returns (`AppNotIdleException` after 60s), and hand-driving the
 * Compose clock instead made the assertions intermittent rather than reliable. Their behaviour
 * (create requires a name, rename writes back) is pinned without Compose in
 * [PresetManagerViewModelTest] / the repository CRUD tests. The delete confirmation, which has no
 * text field, is covered below and is stable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h1200dp")
class PresetManagerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val repo = ControlStateRepository
    private val vm = PresetManagerViewModel()

    private lateinit var originalActiveId: String
    private lateinit var originalDefaultId: String
    private val created = mutableListOf<String>()

    private var exported: String? = null
    private var shared: String? = null
    private var importRequests = 0

    @Before
    fun setUp() {
        originalActiveId = repo.activePreset.value.id
        originalDefaultId = repo.defaultPresetId.value
    }

    @After
    fun tearDown() {
        repo.selectPreset(originalActiveId)
        repo.setDefaultPreset(originalDefaultId)
        created.forEach(repo::deletePreset)
    }

    private fun scratch(name: String, description: String? = null): String =
        vm.createPreset(name, description, null).also(created::add)

    /**
     * A scratch preset with a fully specified shape. Assertions are made against these rather than
     * against the stock catalogue, so nothing here depends on another test class leaving the shared
     * repository default presets untouched.
     */
    private fun scratchWith(
        name: String,
        description: String? = null,
        tags: Set<String> = emptySet(),
        latencyMode: LatencyMode = LatencyMode.LOW_LATENCY,
        dryWet: Int = 60,
        modules: List<EffectModule> = emptyList(),
    ): String {
        val id = scratch(name, description)
        val base = repo.presets.value.first { it.id == id }
        repo.updatePreset(
            base.copy(tags = tags, latencyMode = latencyMode, dryWet = dryWet, modules = modules)
        )
        return id
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                PresetManagerScreen(
                    viewModel = vm,
                    onImportRequest = { importRequests++ },
                    onExportResult = { exported = it },
                    onShareResult = { shared = it },
                )
            }
        }
    }

    /** Waits for the repository's `Dispatchers.Default` active-preset combine to catch up. */
    private fun awaitActive(id: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (System.nanoTime() < deadline) {
            composeRule.waitForIdle()
            if (vm.activePreset.value.id == id) return
            Thread.sleep(2L)
        }
        fail("activePreset never became $id (was ${vm.activePreset.value.id})")
    }

    private val isTextField = SemanticsMatcher("is a text field") { node ->
        node.config.contains(SemanticsActions.SetText)
    }

    private fun click(text: String) {
        composeRule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    /**
     * Icon buttons expose their label on the inner [Icon]; in the merged tree that description is
     * absorbed by whatever wraps it (a text field's trailing icon merges into the field itself), so
     * the click has to be aimed at the icon's clickable parent in the unmerged tree.
     */
    private fun clickDescribed(description: String) {
        composeRule.onNodeWithContentDescription(description, useUnmergedTree = true)
            .onParent()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    /** Narrows the visible list to a single card. The search field appears once there are >3. */
    private fun search(query: String) {
        composeRule.onNode(isTextField).performTextInput(query)
        composeRule.waitForIdle()
    }

    private fun topOf(text: String): Float =
        composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    // --- Listing ------------------------------------------------------------------------------

    @Test
    fun `the header counts the presets that are listed`() {
        scratch("Counted Preset")
        setContent()

        composeRule.onNodeWithText("Preset Manager").assertExists()
        composeRule.onNodeWithText("${vm.presets.value.size} presets").assertExists()
        // A prefix query keeps the search box's own text distinct from the card's title.
        search("Counted Pre")
        composeRule.onNodeWithText("Counted Preset").assertExists()
    }

    @Test
    fun `the active preset is pinned to the top, badged, and offers no second activation`() {
        val activeId = scratch("Zzz Active Preset")
        repo.selectPreset(activeId)
        awaitActive(activeId)
        setContent()

        composeRule.onNodeWithText("ACTIVE").assertExists()
        // Despite sorting last by name, the active preset is hoisted above whatever the repository
        // lists first — the assertion is about the pinning rule, not about a particular preset.
        val firstOther = vm.presets.value.first { it.id != activeId }.name
        assertTrue(
            "the active preset must be pinned above the rest",
            topOf("Zzz Active Preset") < topOf(firstOther),
        )
        // Its primary action is a non-actionable state, not another Activate button.
        composeRule.onNodeWithText("Active").assertIsNotEnabled()
    }

    @Test
    fun `a preset card summarises its latency, mix and enabled effect count`() {
        // Three stages, one of them bypassed: the summary must count the ENABLED two, not all three.
        scratchWith(
            name = "Summary Card",
            description = "three stages, one bypassed",
            latencyMode = LatencyMode.HIGH_QUALITY,
            dryWet = 42,
            modules = listOf(
                EffectModule.Gate(true, -50f, 5f, 120f, 3f),
                EffectModule.Reverb(true, 12f, 20f, 5f, 12f),
                EffectModule.Mix(enabled = false, dryWetPercent = 60f, outputGainDb = 0f),
            ),
        )
        setContent()
        search("Summary Car")

        composeRule.onNodeWithText("High-Quality · 42% wet · 2 effects on").assertExists()
        composeRule.onNodeWithText("three stages, one bypassed").assertExists()
    }

    @Test
    fun `a preset with nothing enabled says so rather than reporting a count`() {
        scratchWith(
            name = "Silent Card",
            modules = listOf(EffectModule.Gate(enabled = false, -50f, 5f, 120f, 3f)),
        )
        setContent()
        search("Silent Car")

        composeRule.onNodeWithText("Low-Latency · 60% wet · no effects on").assertExists()
    }

    // --- Search -------------------------------------------------------------------------------

    @Test
    fun `search narrows by name and the clear action restores the full list`() {
        scratchWith(name = "Zebra Widget")
        scratchWith(name = "Quokka Gadget")
        setContent()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Quokka Gadget"))
        composeRule.onNodeWithText("Quokka Gadget").assertExists()

        search("Zebra")
        composeRule.onNodeWithText("Zebra Widget").assertExists()
        composeRule.onNodeWithText("Quokka Gadget").assertDoesNotExist()
        // The clear affordance only exists while there is a query to clear.
        composeRule.onNodeWithContentDescription("Clear search", useUnmergedTree = true)
            .assertExists()

        clickDescribed("Clear search")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Quokka Gadget"))
        composeRule.onNodeWithText("Quokka Gadget").assertExists()
        composeRule.onNodeWithContentDescription("Clear search", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `search also matches tags`() {
        scratchWith(name = "Tagged Preset", tags = setOf("ZZQ"))
        scratchWith(name = "Plain Preset")
        setContent()

        // The query matches nothing in either NAME; only the tag can produce this hit.
        search("ZZQ")
        composeRule.onNodeWithText("Tagged Preset").assertExists()
        composeRule.onNodeWithText("Plain Preset").assertDoesNotExist()
    }

    @Test
    fun `search also matches the description`() {
        scratchWith(name = "Described Preset", description = "hunts the vorpal snark")
        scratchWith(name = "Bare Preset")
        setContent()

        search("vorpal")
        composeRule.onNodeWithText("Described Preset").assertExists()
        composeRule.onNodeWithText("Bare Preset").assertDoesNotExist()
    }

    @Test
    fun `a search with no matches says so instead of showing a create prompt`() {
        setContent()
        search("zzz-no-such-preset")

        composeRule.onNodeWithText("No presets match your search").assertExists()
        composeRule.onNodeWithText("Try a different name or tag.").assertExists()
        composeRule.onNodeWithText("No presets yet").assertDoesNotExist()
        // Only the always-present header button, not a second one from the empty state.
        composeRule.onAllNodesWithText("New Preset").assertCountEquals(1)
    }

    // --- Activate -----------------------------------------------------------------------------

    @Test
    fun `activate makes the tapped preset the active one`() {
        val pickId = scratch("Activate Me")
        repo.selectPreset(originalActiveId)
        awaitActive(originalActiveId)
        setContent()

        search("Activate Me")
        composeRule.onNodeWithText("Activate").assertIsEnabled()
        click("Activate")

        awaitActive(pickId)
        assertEquals(pickId, vm.activePreset.value.id)
        // The card swaps to the non-actionable active state.
        composeRule.onNodeWithText("ACTIVE").assertExists()
        composeRule.onNodeWithText("Activate").assertDoesNotExist()
    }

    // --- Create -------------------------------------------------------------------------------

    @Test
    fun `duplicate clones the preset under a copy-suffixed name`() {
        scratch("Dupe Source", "notes")
        setContent()
        search("Dupe Source")

        clickDescribed("More actions")
        click("Duplicate")

        val copy = vm.presets.value.firstOrNull { it.name == "Dupe Source (copy)" }
        assertNotNull(copy)
        created += copy!!.id
        assertEquals("notes", copy!!.description)
        // The filter ("Dupe Source") matches the copy too, so it is now on screen.
        composeRule.onNodeWithText("Dupe Source (copy)").assertExists()
    }

    @Test
    fun `set as default badges the preset and then refuses to be re-applied`() {
        val id = scratch("Default Target")
        setContent()
        search("Default Target")

        composeRule.onNodeWithText("DEFAULT").assertDoesNotExist()
        clickDescribed("More actions")
        composeRule.onNodeWithText("Set as default").assertIsEnabled()
        click("Set as default")

        assertEquals(id, vm.defaultPresetId.value)
        composeRule.onNodeWithText("DEFAULT").assertExists()

        clickDescribed("More actions")
        composeRule.onNodeWithText("Default preset").assertIsNotEnabled()
        composeRule.onNodeWithText("Set as default").assertDoesNotExist()
    }

    @Test
    fun `share hands the caller a real preset payload`() {
        scratch("Shared Preset")
        setContent()
        search("Shared Preset")

        clickDescribed("More actions")
        click("Share")

        assertNotNull("share must produce a payload", shared)
        assertTrue(
            "the payload must describe the shared preset",
            shared!!.contains("Shared Preset"),
        )
    }

    // --- Delete -------------------------------------------------------------------------------

    @Test
    fun `the active preset cannot be deleted`() {
        val id = scratch("Undeletable Active")
        repo.selectPreset(id)
        awaitActive(id)
        setContent()
        search("Undeletable Active")

        clickDescribed("More actions")
        composeRule.onNodeWithText("Delete").assertIsNotEnabled()
    }

    @Test
    fun `deleting asks for confirmation and cancelling keeps the preset`() {
        val id = scratch("Kept After Cancel")
        setContent()
        search("Kept After Cancel")

        clickDescribed("More actions")
        composeRule.onNodeWithText("Delete").assertIsEnabled()
        click("Delete")

        composeRule.onNodeWithText("Delete preset?").assertExists()
        composeRule
            .onNodeWithText("\"Kept After Cancel\" will be permanently removed. This can't be undone.")
            .assertExists()

        click("Cancel")
        composeRule.onNodeWithText("Delete preset?").assertDoesNotExist()
        assertNotNull(vm.presets.value.firstOrNull { it.id == id })
    }

    @Test
    fun `confirming the delete dialog removes the preset`() {
        val id = scratch("Doomed Preset")
        setContent()
        search("Doomed Preset")

        clickDescribed("More actions")
        click("Delete")
        // The dialog's confirm button carries the same label as the menu item it came from; the
        // menu is dismissed by now, so this is the dialog's Delete.
        click("Delete")

        assertNull("the preset must be gone", vm.presets.value.firstOrNull { it.id == id })
        created -= id
        composeRule.onNodeWithText("Delete preset?").assertDoesNotExist()
    }

    // --- Import / export ------------------------------------------------------------------------

    @Test
    fun `the import button delegates to the caller's file picker`() {
        setContent()
        assertEquals(0, importRequests)

        clickDescribed("Import preset")

        assertEquals("import must be handed to the caller", 1, importRequests)
    }

    @Test
    fun `export all hands back every listed preset`() {
        scratch("Exported Member")
        setContent()

        clickDescribed("Export all presets")

        assertNotNull(exported)
        val array = org.json.JSONArray(exported)
        assertEquals(vm.presets.value.size, array.length())
        assertTrue(exported!!.contains("Exported Member"))
        assertFalse("export must not be empty", array.length() == 0)
    }
}
