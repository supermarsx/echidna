package com.echidna.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.LegacyPreprocessorControlState
import com.echidna.app.model.SettingsProfile
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose coverage for the Settings screen.
 *
 * The screen is seven independent tabs over one shared [ControlStateRepository], so the tests pin
 * three things: that each tab really swaps in its own sections (not decoration over one long page),
 * that the controls write through to the repository rather than only moving on screen, and that the
 * fail-closed surfaces — the experimental capture-attachment switch and the profile management
 * messages — report the honest state instead of an optimistic one.
 *
 * The repository is a process singleton, so every value this class changes is captured up front and
 * restored in teardown, and profiles created by a test are deleted again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1000dp-h2400dp")
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val repo = ControlStateRepository

    private lateinit var savedSettings: com.echidna.app.model.SettingsState
    private lateinit var preExistingProfileIds: Set<String>

    @Before
    fun captureRepositoryState() {
        savedSettings = repo.settingsState.value
        preExistingProfileIds = repo.settingsProfiles.value.map(SettingsProfile::id).toSet()
        // A stale one-shot focus request from another test must not steer this screen.
        SettingsFocusRequest.consume()
    }

    @After
    fun restoreRepositoryState() {
        repo.settingsProfiles.value
            .map(SettingsProfile::id)
            .filterNot(preExistingProfileIds::contains)
            .forEach(repo::deleteSettingsProfile)
        repo.setKeepScreenOn(savedSettings.keepScreenOn)
        repo.setDebugMode(savedSettings.debugMode)
        repo.setThemeMode(savedSettings.themeMode)
        repo.setDspEngineMode(savedSettings.engineMode)
        repo.setLatencyMode(savedSettings.latencyMode)
        repo.setShowInstallAlerts(savedSettings.showInstallAlerts)
        repo.setMasterEnabled(savedSettings.masterEnabled)
        SettingsFocusRequest.consume()
    }

    private class Callbacks {
        var compatibility = 0
        var whitelist = 0
        var installer = 0
        var alerts = 0
        var setupAgain = 0
        var help = 0
    }

    private fun setContent(
        callbacks: Callbacks = Callbacks(),
        legacyPreprocessor: LegacyPreprocessorControlState = LegacyPreprocessorControlState(),
        legacyRequests: MutableList<Boolean> = mutableListOf(),
    ): Callbacks {
        val viewModel = SettingsViewModel(
            legacyPreprocessorState = MutableStateFlow(legacyPreprocessor),
            legacyPreprocessorSetter = legacyRequests::add,
        )
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    onLaunchCompatibility = { callbacks.compatibility++ },
                    onLaunchWhitelist = { callbacks.whitelist++ },
                    onLaunchInstaller = { callbacks.installer++ },
                    onOpenAlerts = { callbacks.alerts++ },
                    onRunSetupAgain = { callbacks.setupAgain++ },
                    onOpenHelp = { callbacks.help++ },
                )
            }
        }
        return callbacks
    }

    /**
     * Invokes a node's OnClick semantics. The screen is one long scrolling column, so most controls
     * sit outside the viewport; the semantics action exercises the same onClick wiring without a
     * scroll. Real-gesture coverage lives in the instrumentation tests.
     */
    private fun click(text: String) {
        composeRule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    private fun toggle(contentDescription: String) {
        composeRule.onNodeWithContentDescription(contentDescription)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    private fun assertShown(text: String, substring: Boolean = false, count: Int = 1) =
        composeRule.onAllNodesWithText(text, substring = substring).assertCountEquals(count)

    private fun assertAbsent(text: String, substring: Boolean = false) =
        composeRule.onAllNodesWithText(text, substring = substring).assertCountEquals(0)

    // -----------------------------------------------------------------------
    // Tabs
    // -----------------------------------------------------------------------

    @Test
    fun `the screen opens on Alerts with the header and the always-visible Help link`() {
        setContent()
        assertShown("Settings")
        assertShown("Engine", substring = true, count = 2)
        assertShown("Help & Docs")
        assertShown("Open Help & Docs")
        // The Alerts tab's own sections.
        assertShown("Advisory Alerts")
        assertShown("Alert Preferences")
        // and nothing from the other six.
        assertAbsent("Theme")
        assertAbsent("Profile Management")
        assertAbsent("Safety and Failsafe")
    }

    @Test
    fun `each tab swaps in its own sections`() {
        setContent()

        click("Appearance")
        assertShown("Theme")
        assertShown("Theme mode")
        assertShown("Accent color")
        assertAbsent("Alert Preferences")

        click("Startup")
        assertShown("Startup and System Integration")
        assertShown("Notification and Control")
        assertAbsent("Theme mode")

        click("Engine")
        assertShown("Engine module")
        assertShown("Engine and DSP Behavior")
        assertShown("Experimental Capture Attachment")
        assertAbsent("Startup and System Integration")

        click("Safety")
        assertShown("Safety and Failsafe")
        assertShown("Panic Bypass")
        assertAbsent("Engine and DSP Behavior")

        click("Diagnostics")
        assertShown("Diagnostics and Developer")
        assertShown("Debug mode")
        assertAbsent("Safety and Failsafe")

        click("Profiles")
        assertShown("Profile Management")
        assertAbsent("Diagnostics and Developer")

        click("Alerts")
        assertShown("Alert Preferences")
        assertAbsent("Profile Management")
    }

    @Test
    fun `a pending focus request opens Settings straight on the Engine tab`() {
        SettingsFocusRequest.request(SettingsFocus.ENGINE)
        setContent()

        assertShown("Engine and DSP Behavior")
        assertAbsent("Alert Preferences")
        // The one-shot request is consumed, so a later composition lands on the default tab again.
        assertEquals(null, SettingsFocusRequest.consume())
    }

    // -----------------------------------------------------------------------
    // Navigation callbacks
    // -----------------------------------------------------------------------

    @Test
    fun `the link buttons invoke exactly their own callback`() {
        val callbacks = setContent()

        click("Open Help & Docs")
        click("Open Alerts")
        assertEquals(1, callbacks.help)
        assertEquals(1, callbacks.alerts)

        click("Startup")
        click("Compatibility Wizard")
        click("Per-App Whitelist")
        composeRule.onNodeWithTag("settings_run_setup_again")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        click("Engine")
        click("Install engine")

        assertEquals(1, callbacks.compatibility)
        assertEquals(1, callbacks.whitelist)
        assertEquals(1, callbacks.setupAgain)
        assertEquals(1, callbacks.installer)
    }

    @Test
    fun `an uninstalled engine module offers the guided installer`() {
        // Nothing binds the control service in a unit test, so module status is genuinely unknown
        // and the section must take its not-installed branch rather than assume the module is there.
        setContent()
        click("Engine")
        assertShown("The Echidna engine module is not installed.", substring = true)
        assertShown("Install engine")
        assertAbsent("Install or update engine")
    }

    // -----------------------------------------------------------------------
    // Controls write through to the repository
    // -----------------------------------------------------------------------

    @Test
    fun `the keep-screen-on switch writes through to the repository`() {
        repo.setKeepScreenOn(false)
        setContent()
        click("Appearance")

        toggle("Keep screen on")
        assertTrue(repo.settingsState.value.keepScreenOn)

        toggle("Keep screen on")
        assertFalse(repo.settingsState.value.keepScreenOn)
    }

    @Test
    fun `the diagnostics switches write through to the repository`() {
        repo.setDebugMode(false)
        setContent()
        click("Diagnostics")

        toggle("Debug mode")
        assertTrue(repo.settingsState.value.debugMode)
        assertShown("Status poll interval ${repo.settingsState.value.statusPollIntervalSeconds} s")
    }

    @Test
    fun `the alert preference switches write through to the repository`() {
        repo.setShowInstallAlerts(true)
        setContent()

        toggle("Incomplete install alerts")
        assertFalse(repo.settingsState.value.showInstallAlerts)
        // The thresholds are rendered from the live state, not a local copy.
        assertShown("Latency alert threshold ${repo.settingsState.value.alertLatencyThresholdMs} ms")
        assertShown("XRun alert threshold ${repo.settingsState.value.alertXrunThreshold}")
    }

    @Test
    fun `choosing a theme mode persists it and marks the chosen button selected`() {
        repo.setThemeMode(com.echidna.app.model.ThemeMode.SYSTEM)
        setContent()
        click("Appearance")

        // The selected choice renders as a disabled filled button; the others stay clickable.
        composeRule.onNodeWithText("System").assertIsNotEnabled()
        composeRule.onNodeWithText("Dark").assertIsEnabled()

        click("Dark")
        assertEquals(com.echidna.app.model.ThemeMode.DARK, repo.settingsState.value.themeMode)
        composeRule.onNodeWithText("Dark").assertIsNotEnabled()
        composeRule.onNodeWithText("System").assertIsEnabled()
    }

    @Test
    fun `choosing an engine mode and latency target persists both`() {
        setContent()
        click("Engine")

        click(com.echidna.app.model.DspEngineMode.COMPATIBILITY.label)
        assertEquals(
            com.echidna.app.model.DspEngineMode.COMPATIBILITY,
            repo.settingsState.value.engineMode,
        )
        // The description under the picker follows the chosen mode.
        assertShown(com.echidna.app.model.DspEngineMode.COMPATIBILITY.description)

        click("High-Quality (30 ms)")
        assertEquals(
            com.echidna.app.model.LatencyMode.HIGH_QUALITY,
            repo.settingsState.value.latencyMode,
        )
    }

    // -----------------------------------------------------------------------
    // Experimental capture attachment — the fail-closed switch
    // -----------------------------------------------------------------------

    @Test
    fun `the capture-attachment switch is disabled and honest before the service loads`() {
        setContent(legacyPreprocessor = LegacyPreprocessorControlState())
        click("Engine")

        assertShown("Unavailable until the control service connects")
        composeRule.onNodeWithContentDescription(LEGACY_TITLE).assertIsNotEnabled()
    }

    @Test
    fun `an unavailable service still reports the last confirmed attachment state`() {
        setContent(
            legacyPreprocessor = LegacyPreprocessorControlState(
                enabled = true,
                loaded = true,
                available = false,
                error = "Control service disconnected.",
            ),
        )
        click("Engine")

        assertShown("Unavailable — last confirmed enabled")
        assertShown("Control service disconnected.")
        composeRule.onNodeWithContentDescription(LEGACY_TITLE).assertIsNotEnabled()
    }

    @Test
    fun `a confirmed-off service enables the switch and delegates the request`() {
        val requests = mutableListOf<Boolean>()
        setContent(
            legacyPreprocessor = LegacyPreprocessorControlState(
                enabled = false,
                loaded = true,
                available = true,
            ),
            legacyRequests = requests,
        )
        click("Engine")

        assertShown("Attachment permission disabled (default)")
        composeRule.onNodeWithContentDescription(LEGACY_TITLE).assertIsEnabled()

        toggle(LEGACY_TITLE)
        // The switch asks the service; it must not flip its own confirmed state optimistically.
        assertEquals(listOf(true), requests)
        assertShown("Attachment permission disabled (default)")
    }

    @Test
    fun `an in-flight update locks the switch and says it is saving`() {
        setContent(
            legacyPreprocessor = LegacyPreprocessorControlState(
                enabled = true,
                loaded = true,
                available = true,
                updating = true,
            ),
        )
        click("Engine")

        assertShown("Saving and confirming…")
        composeRule.onNodeWithContentDescription(LEGACY_TITLE).assertIsNotEnabled()
    }

    // -----------------------------------------------------------------------
    // Profile management
    // -----------------------------------------------------------------------

    @Test
    fun `saving a profile without a name is refused`() {
        setContent()
        click("Profiles")

        click("Save")
        assertShown("Profile name is required.")
        assertEquals(preExistingProfileIds.size, repo.settingsProfiles.value.size)
    }

    @Test
    fun `a named profile is created, applied, exported and deleted with honest messages`() {
        setContent()
        click("Profiles")

        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("Screen Test Profile")
        click("Save")
        assertShown("Settings profile saved.")
        assertEquals(preExistingProfileIds.size + 1, repo.settingsProfiles.value.size)
        // The new profile becomes the selection, so its summary line renders.
        assertShown("Screen Test Profile", count = 1)

        click("Apply")
        assertShown("Settings profile applied.")

        click("Export")
        assertShown("Settings profile JSON ready.")
        assertShown("Export JSON")

        click("Delete")
        assertShown("Settings profile deleted.")
        assertEquals(preExistingProfileIds.size, repo.settingsProfiles.value.size)
    }

    @Test
    fun `exporting the current settings produces a JSON field`() {
        setContent()
        click("Profiles")

        assertAbsent("Export JSON")
        click("Export Current Settings")
        assertShown("Current settings JSON ready.")
        assertShown("Export JSON")
    }

    @Test
    fun `import is disabled while empty and reports a failure for unusable JSON`() {
        setContent()
        click("Profiles")

        composeRule.onNodeWithText("Import Settings Profile").assertIsNotEnabled()

        composeRule.onAllNodes(hasSetTextAction()).let { it[it.fetchSemanticsNodes().size - 1] }
            .performTextInput("not json at all")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Import Settings Profile").assertIsEnabled()
        click("Import Settings Profile")
        assertShown("Import failed. Check the JSON and try again.")
        assertEquals(preExistingProfileIds.size, repo.settingsProfiles.value.size)
    }

    private companion object {
        const val LEGACY_TITLE = "Legacy AudioFlinger preprocessor (experimental)"
    }
}
