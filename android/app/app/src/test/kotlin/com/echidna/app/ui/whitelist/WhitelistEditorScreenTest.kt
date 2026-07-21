package com.echidna.app.ui.whitelist

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.echidna.app.data.ControlStateRepository
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
 * Robolectric + Compose coverage for [WhitelistEditorScreen].
 *
 * No packages are installed and no control service is bound in a JVM test, so the editor's honest
 * starting point is an empty catalog. That makes the state-dependent branches directly testable:
 * empty vs populated, installed-only vs the full curated catalog, enabled vs disabled rows, and a
 * search that finds nothing. What is pinned throughout is that a row only ever claims what the
 * data says — a curated suggestion that is not present says "Not installed on this device" rather
 * than being rendered as if it were there.
 *
 * The whitelist lives in the process-wide [ControlStateRepository]; teardown disables and unbinds
 * every package a test touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h1200dp")
class WhitelistEditorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val repo = ControlStateRepository
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val touched = mutableListOf<String>()

    /**
     * The whitelist lives in the process-wide repository singleton, and these tests assert on an
     * empty catalogue as their starting point. Gradle gives no guarantee about test-class order, so
     * rather than hope nobody else left an entry behind, take a snapshot and clear it here, then put
     * it back in teardown. That makes the class independent of what ran before it.
     */
    private lateinit var originalWhitelist: Map<String, Boolean>
    private lateinit var originalBindings: Map<String, String>

    @Before
    fun setUp() {
        val snapshot = repo.whitelistBindings.value
        originalWhitelist = snapshot.whitelist
        originalBindings = snapshot.appBindings
        originalBindings.keys.forEach { repo.setAppPresetBinding(it, "") }
        originalWhitelist.keys.forEach { repo.updateWhitelist(it, false) }
    }

    @After
    fun tearDown() {
        touched.forEach { pkg ->
            repo.setAppPresetBinding(pkg, "")
            repo.updateWhitelist(pkg, false)
        }
        originalWhitelist.forEach { (pkg, enabled) -> repo.updateWhitelist(pkg, enabled) }
        originalBindings.forEach { (pkg, presetId) -> repo.setAppPresetBinding(pkg, presetId) }
    }

    private fun screen(): WhitelistEditorViewModel {
        val vm = WhitelistEditorViewModel(app)
        composeRule.setContent { MaterialTheme { WhitelistEditorScreen(viewModel = vm) } }
        // The init refresh hops through Dispatchers.IO; wait for it to land before asserting.
        awaitNotLoading(vm)
        return vm
    }

    private fun awaitNotLoading(vm: WhitelistEditorViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (System.nanoTime() < deadline) {
            composeRule.waitForIdle()
            if (!vm.loading.value) return
            Thread.sleep(2L)
        }
        fail("the whitelist editor never finished loading")
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

    /** Adds a package through the screen's own "Add by package" affordance. */
    private fun addByPackage(packageName: String) {
        touched += packageName
        click("Add by package")
        composeRule.onNode(isTextField and hasText("Package name")).performTextInput(packageName)
        composeRule.waitForIdle()
        click("Add")
    }

    // --- Empty catalog --------------------------------------------------------------------------

    @Test
    fun `with nothing installed the editor says so instead of listing anything`() {
        val vm = screen()

        composeRule.onNodeWithText("Per-App Whitelist").assertExists()
        composeRule.onNodeWithText("Choose which apps the voice engine processes").assertExists()
        assertEquals(emptyList<AppEntry>(), vm.entries.value)
        composeRule.onNodeWithText("No apps found").assertExists()
        composeRule.onNodeWithText("No apps match your search").assertDoesNotExist()
        composeRule.onNodeWithText("0 enabled | 0 suggested").assertExists()
        composeRule.onNodeWithText("Reload").assertIsEnabled()
    }

    // --- Installed-only filter ------------------------------------------------------------------

    @Test
    fun `turning off installed-only surfaces the curated catalog and labels it honestly`() {
        val vm = screen()
        assertTrue("installed-only is the persisted default", vm.onlyInstalled.value)
        composeRule.onNodeWithText("Only apps installed on this device").assertExists()

        click("Only installed")
        awaitNotLoading(vm)

        assertFalse(vm.onlyInstalled.value)
        composeRule.onNodeWithText("Showing all curated suggestions").assertExists()
        composeRule.onNodeWithText("Only apps installed on this device").assertDoesNotExist()
        composeRule.onNodeWithText("No apps found").assertDoesNotExist()
        assertTrue("the curated catalog must now be listed", vm.entries.value.isNotEmpty())
        composeRule
            .onNodeWithText("Common voice, calls, social, games, and streaming apps (all, incl. not installed)")
            .assertExists()
        // Nothing is installed here, so every surfaced row must admit that.
        assertTrue(vm.entries.value.none { it.installed })
        assertTrue(
            "every rendered row must admit the app is absent",
            composeRule.onAllNodesWithText("Not installed on this device")
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    // --- Add by package -------------------------------------------------------------------------

    @Test
    fun `add by package refuses a blank name and adds an enabled row for a valid one`() {
        val vm = screen()

        click("Add by package")
        composeRule.onNodeWithText("Package name").assertExists()
        composeRule.onNodeWithText("Add").assertIsNotEnabled()

        composeRule.onNode(isTextField and hasText("Package name")).performTextInput("com.discord")
        touched += "com.discord"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Add").assertIsEnabled()
        click("Add")

        val entry = vm.entries.value.firstOrNull { it.packageName == "com.discord" }
        assertNotNull("the package must be added", entry)
        assertTrue("an added package starts enabled", entry!!.enabled)
        assertTrue(repo.whitelistBindings.value.whitelist["com.discord"] == true)

        // The curated label is used, the package id is always shown alongside it, and the row is
        // honest that the app is not actually present on this device.
        composeRule.onNodeWithText("Discord").assertExists()
        composeRule.onNodeWithText("com.discord").assertExists()
        composeRule.onNodeWithText("Not installed on this device").assertExists()
        // Once: as a category filter chip, and again as the row's own metadata chip.
        composeRule.onAllNodesWithText("Voice chat").assertCountEquals(2)
        composeRule.onNodeWithText("1 enabled | 0 suggested").assertExists()
        // The add field collapses again after a successful add.
        composeRule.onNodeWithText("Package name").assertDoesNotExist()
        composeRule.onNodeWithText("Add by package").assertExists()
    }

    @Test
    fun `a package name that is not a package name is rejected`() {
        val vm = screen()

        click("Add by package")
        composeRule.onNode(isTextField and hasText("Package name")).performTextInput("notapackagename")
        composeRule.waitForIdle()
        click("Add")

        assertEquals("an invalid id must not become a row", emptyList<AppEntry>(), vm.entries.value)
        composeRule.onNodeWithText("No apps found").assertExists()
    }

    // --- Enable / disable / remove ----------------------------------------------------------------

    @Test
    fun `disabling a row hides its per-app binding controls and moves it out of Enabled`() {
        val vm = screen()
        addByPackage("com.discord")

        composeRule.onNodeWithText("Enabled").assertExists()
        composeRule.onNodeWithText("Preset: Default").assertExists()
        composeRule.onNodeWithText("Remove").assertExists()
        composeRule.onNode(isToggleable()).assertIsOn()

        composeRule.onNode(isToggleable()).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertFalse(vm.entries.value.single().enabled)
        assertEquals(false, repo.whitelistBindings.value.whitelist["com.discord"])
        composeRule.onNode(isToggleable()).assertIsOff()
        // The binding chip and the destructive action only exist for an enabled app.
        composeRule.onNodeWithText("Preset: Default").assertDoesNotExist()
        composeRule.onNodeWithText("Remove").assertDoesNotExist()
        // It is now a suggestion rather than an enabled app, and the tally says so.
        composeRule.onNodeWithText("0 enabled | 1 suggested").assertExists()
        // The "Suggested" section header, plus the row's own suggestion chip.
        composeRule.onAllNodesWithText("Suggested").assertCountEquals(2)
    }

    @Test
    fun `remove drops the row entirely`() {
        val vm = screen()
        addByPackage("com.discord")

        click("Remove")

        assertEquals(emptyList<AppEntry>(), vm.entries.value)
        assertEquals(false, repo.whitelistBindings.value.whitelist["com.discord"])
        composeRule.onNodeWithText("No apps found").assertExists()
        composeRule.onNodeWithText("Discord").assertDoesNotExist()
    }

    // --- Per-app preset binding -------------------------------------------------------------------

    @Test
    fun `binding a preset to an app writes the binding and relabels the chip`() {
        val vm = screen()
        addByPackage("com.discord")
        val preset = repo.presets.value.first()

        click("Preset: Default")
        click(preset.name)

        assertEquals(preset.id, vm.entries.value.single().presetId)
        assertEquals(preset.id, repo.whitelistBindings.value.appBindings["com.discord"])
        composeRule.onNodeWithText("Preset: ${preset.name}").assertExists()
        composeRule.onNodeWithText("Preset: Default").assertDoesNotExist()
        // The bound state is also advertised as a metadata chip on the row.
        composeRule.onNodeWithText("Preset bound").assertExists()
    }

    @Test
    fun `choosing Default clears an existing binding`() {
        val vm = screen()
        addByPackage("com.discord")
        val preset = repo.presets.value.first()

        click("Preset: Default")
        click(preset.name)
        click("Preset: ${preset.name}")
        click("Default")

        assertEquals("", vm.entries.value.single().presetId)
        assertNull(repo.whitelistBindings.value.appBindings["com.discord"])
        composeRule.onNodeWithText("Preset: Default").assertExists()
    }

    // --- Search + category filter ------------------------------------------------------------------

    @Test
    fun `search narrows to matching apps and says so when nothing matches`() {
        screen()
        addByPackage("com.discord")

        composeRule.onNode(isTextField and hasText("Search apps")).performTextInput("zzz-no-such-app")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No apps match your search").assertExists()
        composeRule.onNodeWithText("No apps found").assertDoesNotExist()
        composeRule.onNodeWithText("com.discord").assertDoesNotExist()

        clickDescribed("Clear search")
        composeRule.onNodeWithText("Discord").assertExists()
        composeRule.onNodeWithContentDescription("Clear search", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `search matches the package id as well as the label`() {
        screen()
        addByPackage("com.discord")

        composeRule.onNode(isTextField and hasText("Search apps")).performTextInput("com.disc")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Discord").assertExists()
        composeRule.onNodeWithText("1 enabled | 0 suggested").assertExists()
    }

    @Test
    fun `a category chip filters the list on top of the search query`() {
        val vm = screen()
        // The chip row is derived from the whole catalog, so surface it first.
        click("Only installed")
        awaitNotLoading(vm)
        composeRule.onNode(isTextField and hasText("Search apps")).performTextInput("Discord")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("com.discord").assertExists()

        // Discord is catalogued as Voice chat, so filtering to Calls must exclude it.
        click("Calls")
        // The row is gone (the remaining "Discord" node is the search box's own text).
        composeRule.onNodeWithText("com.discord").assertDoesNotExist()
        composeRule.onNodeWithText("No apps match your search").assertExists()

        click("All")
        composeRule.onNodeWithText("com.discord").assertExists()
    }
}
