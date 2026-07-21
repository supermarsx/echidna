package com.echidna.app.ui.onboarding

import android.Manifest
import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.AccentColor
import com.echidna.app.model.ThemeMode
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose coverage for the individual wizard pages in `OnboardingSteps.kt`.
 *
 * [OnboardingWizardHostTest] already pins the wizard chrome (progress, next/back, the recovery
 * gate as seen by the host). This class renders each step's *content* and pins what the step
 * itself claims: the state-dependent branches genuinely differ (granted vs denied permission,
 * dynamic colour on vs off, notification on vs off), the callbacks write through the shared
 * repository, and every probe that cannot run in this environment renders the honest
 * "unavailable"/"none found" placeholder rather than inventing a value.
 *
 * The repository is a process singleton, so each test sets the state it asserts on and teardown
 * restores the values it touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnboardingStepsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val repo = ControlStateRepository
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val originalSettings = repo.settingsState.value
    private val originalActivePresetId = repo.activePreset.value.id
    private val originalOnboardingComplete = repo.onboardingComplete.value
    private val scratchPresetIds = mutableListOf<String>()

    @After
    fun tearDown() {
        repo.setThemeMode(originalSettings.themeMode)
        repo.setDynamicColor(originalSettings.dynamicColor)
        repo.setAccentColor(originalSettings.accentColor)
        repo.setNotificationEnabled(originalSettings.persistentNotification)
        repo.setHighPriorityNotification(originalSettings.highPriorityNotification)
        repo.setQuickControlsEnabled(originalSettings.quickControlsEnabled)
        repo.setShowInstallAlerts(originalSettings.showInstallAlerts)
        repo.setShowBridgeAlerts(originalSettings.showBridgeAlerts)
        repo.setShowHardwareAlerts(originalSettings.showHardwareAlerts)
        repo.setShowInstallMixupAlerts(originalSettings.showInstallMixupAlerts)
        repo.selectPreset(originalActivePresetId)
        repo.setOnboardingComplete(originalOnboardingComplete)
        scratchPresetIds.forEach(repo::deletePreset)
    }

    /**
     * An accent swatch is the only clickable node in the theme step that carries no text of its own
     * and is not the Material-You switch. They compose in [AccentColor.entries] order.
     */
    private val isAccentSwatch = SemanticsMatcher("is an accent swatch") { node ->
        node.config.contains(SemanticsActions.OnClick) &&
            !node.config.contains(SemanticsProperties.ToggleableState) &&
            node.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty()
    }

    /**
     * Renders one step's content inside a scrollable host so `performScrollTo` can reach it. The
     * view model is given a single-step flow so its navigation state agrees with what is on screen
     * (the recovery gate is evaluated against `state.step`, not against what was composed).
     */
    private fun step(
        step: OnboardingStep,
        onOpenInstaller: () -> Unit = {},
        onOpenLab: () -> Unit = {},
    ): OnboardingViewModel {
        val vm = OnboardingViewModel(steps = listOf(step))
        composeRule.setContent {
            MaterialTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OnboardingStepContent(
                        step = step,
                        viewModel = vm,
                        onOpenInstaller = onOpenInstaller,
                        onOpenLab = onOpenLab,
                    )
                }
            }
        }
        return vm
    }

    /** Bounded settle for repository flows republished off the test thread. */
    private fun await(what: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (System.nanoTime() < deadline) {
            composeRule.waitForIdle()
            if (predicate()) return
            Thread.sleep(2L)
        }
        fail("timed out waiting for $what")
    }

    private fun scratchPreset(name: String): String =
        repo.createPreset(name, null, null).also(scratchPresetIds::add)

    // --- Welcome ------------------------------------------------------------------------------

    @Test
    fun `welcome names the no-root path and refuses to promise interception works`() {
        step(OnboardingStep.WELCOME)

        composeRule.onNodeWithTag(OnboardingTestTags.step(OnboardingStep.WELCOME)).assertExists()
        composeRule.onNodeWithText("What actually works").assertExists()
        composeRule.onNodeWithText("The Lab tab works with no root", substring = true).assertExists()
        composeRule
            .onNodeWithText("needs a rooted device with Magisk + Zygisk", substring = true)
            .assertExists()
        // The honest caveat is the point of this page — installing proves nothing.
        composeRule
            .onNodeWithText("Installing the app never proves your device can intercept audio", substring = true)
            .assertExists()
    }

    // --- Permissions --------------------------------------------------------------------------

    @Test
    fun `permissions offers a grant affordance and says a denial is fine`() {
        Shadows.shadowOf(app).denyPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        step(OnboardingStep.PERMISSIONS)

        composeRule.onNodeWithText("Microphone").assertExists()
        // API 33 host: the notifications row is the SDK-gated branch and must be present.
        composeRule.onNodeWithText("Notifications").assertExists()
        composeRule
            .onNodeWithText("Notification permission is automatic on this Android version.")
            .assertDoesNotExist()
        composeRule.onAllNodesWithText("Grant").assertCountEquals(2)
        composeRule
            .onAllNodesWithText("Denied for now — that's fine, this step is optional.")
            .assertCountEquals(2)
        composeRule.onAllNodesWithText("Granted").assertCountEquals(0)
    }

    @Test
    fun `permissions reports held permissions as granted instead of re-asking`() {
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        step(OnboardingStep.PERMISSIONS)

        composeRule.onAllNodesWithText("Granted").assertCountEquals(2)
        composeRule.onAllNodesWithText("Grant").assertCountEquals(0)
        composeRule
            .onAllNodesWithText("Denied for now — that's fine, this step is optional.")
            .assertCountEquals(0)
    }

    // --- Compatibility ------------------------------------------------------------------------

    @Test
    fun `compatibility renders the unavailable probe verdict rather than a fabricated tally`() {
        val vm = step(OnboardingStep.COMPATIBILITY)
        await("the compatibility probe to publish a result") { vm.compatibility.value != null }
        composeRule.waitForIdle()

        // SELinux "unknown" and the unbound control service are both counted as failures.
        composeRule.onNodeWithText("Significant gaps").assertExists()
        composeRule.onNodeWithText("0 pass · 0 warn · 2 fail").assertExists()
        composeRule.onNodeWithText("SELinux: Unknown (control service unavailable)").assertExists()
        composeRule.onNodeWithText("Looks capable").assertDoesNotExist()
        composeRule.onNodeWithText("Some warnings").assertDoesNotExist()
    }

    // --- Recovery (the one non-skippable safety gate) -------------------------------------------

    @Test
    fun `recovery will not let the wizard advance until the plan is acknowledged`() {
        val vm = step(OnboardingStep.RECOVERY)

        composeRule.onNodeWithText("Your recovery plan").assertExists()
        composeRule
            .onNodeWithText("/data/adb/modules/echidna/disable", substring = true)
            .assertExists()
        composeRule
            .onNodeWithText("Acknowledge to continue. (This is the one step you can't skip past.)")
            .assertExists()
        composeRule.onNodeWithTag(OnboardingTestTags.RECOVERY_ACK).assertIsOff()

        // The gate is real: next() and skipStep() are both refused while unacknowledged.
        assertFalse(vm.state.value.canAdvance)
        vm.next()
        assertEquals(OnboardingStep.RECOVERY, vm.state.value.step)
        vm.skipStep()
        assertEquals(OnboardingStep.RECOVERY, vm.state.value.step)

        composeRule.onNodeWithTag(OnboardingTestTags.RECOVERY_ACK).performScrollTo().performClick()
        composeRule.waitForIdle()

        assertTrue(vm.state.value.recoveryAcknowledged)
        assertTrue(vm.state.value.canAdvance)
        composeRule.onNodeWithTag(OnboardingTestTags.RECOVERY_ACK).assertIsOn()
        composeRule
            .onNodeWithText("Acknowledge to continue. (This is the one step you can't skip past.)")
            .assertDoesNotExist()
    }

    @Test
    fun `recovery can be un-acknowledged from its label, restoring the gate`() {
        val vm = step(OnboardingStep.RECOVERY)
        val label = "I understand how to recover if a root module bootloops my device."

        composeRule.onNodeWithText(label).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue("the label must toggle the same state as the box", vm.state.value.recoveryAcknowledged)

        composeRule.onNodeWithText(label).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertFalse(vm.state.value.recoveryAcknowledged)
        assertFalse(vm.state.value.canAdvance)
        composeRule
            .onNodeWithText("Acknowledge to continue. (This is the one step you can't skip past.)")
            .assertExists()
    }

    // --- Theme --------------------------------------------------------------------------------

    @Test
    fun `theme mode buttons write the chosen mode through to settings`() {
        repo.setThemeMode(ThemeMode.SYSTEM)
        step(OnboardingStep.THEME)

        composeRule.onNodeWithText("System").assertExists()
        composeRule.onNodeWithText("Light").assertExists()

        composeRule.onNodeWithText("Dark").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(ThemeMode.DARK, repo.settingsState.value.themeMode)

        composeRule.onNodeWithText("Light").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(ThemeMode.LIGHT, repo.settingsState.value.themeMode)
    }

    @Test
    fun `the accent row is disabled while Material You owns the palette and enabled once it is off`() {
        repo.setDynamicColor(true)
        step(OnboardingStep.THEME)

        composeRule.onNodeWithText("Accent (disabled while Material You is on)").assertExists()
        // Every accent label still renders, but none of the swatches is clickable while
        // Material You owns the palette — the row is genuinely inert, not merely greyed.
        composeRule.onNodeWithText(AccentColor.TEAL.label).assertExists()
        composeRule.onAllNodes(isAccentSwatch).assertCountEquals(0)

        composeRule.onNode(isToggleable()).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertFalse(repo.settingsState.value.dynamicColor)
        composeRule.onNodeWithText("Accent").assertExists()
        composeRule.onNodeWithText("Accent (disabled while Material You is on)").assertDoesNotExist()

        // With Material You off every accent becomes selectable; they compose in enum order.
        composeRule.onAllNodes(isAccentSwatch).assertCountEquals(AccentColor.entries.size)
        val teal = AccentColor.entries.indexOf(AccentColor.TEAL)
        composeRule.onAllNodes(isAccentSwatch)[teal].performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(AccentColor.TEAL, repo.settingsState.value.accentColor)
    }

    // --- Preset -------------------------------------------------------------------------------

    @Test
    fun `preset lists every preset and selecting one makes it active`() {
        val pickId = scratchPreset("Onboarding Pick")
        repo.selectPreset(originalActivePresetId)
        await("the original preset to be active") { repo.activePreset.value.id == originalActivePresetId }

        step(OnboardingStep.PRESET)
        composeRule.onNodeWithText("Onboarding Pick").assertExists()

        composeRule.onNodeWithText("Onboarding Pick").performScrollTo().performClick()
        await("the tapped preset to become active") { repo.activePreset.value.id == pickId }
        assertEquals(pickId, repo.activePreset.value.id)
    }

    // --- Whitelist ----------------------------------------------------------------------------

    @Test
    fun `whitelist says it found no launchable apps instead of listing invented ones`() {
        step(OnboardingStep.WHITELIST)
        // The LaunchedEffect resolves to an empty list here (no launcher activities are registered
        // in the JVM environment); the step must say so rather than render a fabricated app list.
        await("the installed-app scan to finish") {
            composeRule.onAllNodesWithText("Loading installed apps…").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("No launchable apps found to suggest.").assertExists()
    }

    // --- Alerts -------------------------------------------------------------------------------

    @Test
    fun `the advisory alerts master reflects and flips all four categories`() {
        repo.setShowInstallAlerts(true)
        repo.setShowBridgeAlerts(true)
        repo.setShowHardwareAlerts(true)
        repo.setShowInstallMixupAlerts(true)
        step(OnboardingStep.ALERTS)

        val master = composeRule.onNode(
            isToggleable() and hasAnyAncestor(hasTestTag(OnboardingTestTags.ALERTS_TOGGLE))
        )
        master.assertIsOn()

        master.performScrollTo().performClick()
        composeRule.waitForIdle()
        val off = repo.settingsState.value
        assertFalse(off.showInstallAlerts)
        assertFalse(off.showBridgeAlerts)
        assertFalse(off.showHardwareAlerts)
        assertFalse(off.showInstallMixupAlerts)
        master.assertIsOff()

        master.performScrollTo().performClick()
        composeRule.waitForIdle()
        val on = repo.settingsState.value
        assertTrue(on.showInstallAlerts && on.showBridgeAlerts && on.showHardwareAlerts && on.showInstallMixupAlerts)
    }

    @Test
    fun `the alerts master reads as off when only some categories are on`() {
        repo.setShowInstallAlerts(true)
        repo.setShowBridgeAlerts(true)
        repo.setShowHardwareAlerts(true)
        repo.setShowInstallMixupAlerts(false)
        step(OnboardingStep.ALERTS)

        composeRule
            .onNode(isToggleable() and hasAnyAncestor(hasTestTag(OnboardingTestTags.ALERTS_TOGGLE)))
            .assertIsOff()
    }

    // --- High-priority notification -------------------------------------------------------------

    @Test
    fun `high priority is gated on the controls notification being enabled`() {
        repo.setNotificationEnabled(false)
        step(OnboardingStep.HIGH_PRIORITY_NOTIFICATION)

        val highPriority = composeRule.onNode(
            isToggleable() and hasAnyAncestor(hasTestTag(OnboardingTestTags.HIGH_PRIORITY_TOGGLE))
        )
        highPriority.assertIsNotEnabled()
        composeRule
            .onNodeWithText("Enable the controls notification above for the high-priority option to matter.")
            .assertExists()

        repo.setNotificationEnabled(true)
        composeRule.waitForIdle()
        highPriority.assertIsEnabled()
        composeRule
            .onNodeWithText("Enable the controls notification above for the high-priority option to matter.")
            .assertDoesNotExist()

        repo.setHighPriorityNotification(false)
        composeRule.waitForIdle()
        highPriority.performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(repo.settingsState.value.highPriorityNotification)
    }

    // --- Quick Settings tile ---------------------------------------------------------------------

    @Test
    fun `the add-tile action is only offered while the tile itself is enabled`() {
        repo.setQuickControlsEnabled(false)
        step(OnboardingStep.QUICK_TILE)

        // API 33 host: the programmatic add button is the SDK-gated branch.
        composeRule.onNodeWithText("Add tile now").assertIsNotEnabled()
        composeRule
            .onNodeWithText("On this Android version, add the tile manually", substring = true)
            .assertDoesNotExist()

        composeRule
            .onNode(isToggleable() and hasAnyAncestor(hasTestTag(OnboardingTestTags.QUICK_TILE_TOGGLE)))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertTrue(repo.settingsState.value.quickControlsEnabled)
        composeRule.onNodeWithText("Add tile now").assertIsEnabled()
    }

    // --- Engine -------------------------------------------------------------------------------

    @Test
    fun `engine reports no Magisk or Zygisk detected rather than claiming an install`() {
        val vm = step(OnboardingStep.ENGINE, onOpenInstaller = { fail("installer must not auto-open") })
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Detection").assertExists()
        composeRule.onNodeWithText("Zygisk enabled").assertExists()
        composeRule.onNodeWithText("Echidna module installed").assertExists()
        // Both detection rows must read "no" — nothing may be reported as present without a probe.
        composeRule.onAllNodesWithText("no").assertCountEquals(2)
        composeRule.onAllNodesWithText("yes").assertCountEquals(0)
        composeRule
            .onNodeWithText("No Magisk/Zygisk detected.", substring = true)
            .assertExists()
        // The install hand-off is not offered when nothing was detected.
        composeRule.onNodeWithText("Open installer").assertDoesNotExist()
        composeRule.onNodeWithText("Open Magisk").assertDoesNotExist()
        assertEquals(null, vm.moduleStatus.value)
    }

    // --- Lab ----------------------------------------------------------------------------------

    @Test
    fun `lab admits the DSP engine is unavailable in a lite build and still offers the Lab`() {
        var opened = false
        step(OnboardingStep.LAB, onOpenLab = { opened = true })

        composeRule
            .onNodeWithText("DSP engine: unavailable (lite build)", substring = true)
            .assertExists()
        composeRule.onNodeWithText("DSP engine: loaded", substring = true).assertDoesNotExist()

        composeRule.onNodeWithText("Open the Lab anyway").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue("the Lab hand-off must still fire", opened)
    }

    // --- Done ---------------------------------------------------------------------------------

    @Test
    fun `the summary reports the settings that are actually off`() {
        repo.setThemeMode(ThemeMode.LIGHT)
        repo.setShowInstallAlerts(false)
        repo.setShowBridgeAlerts(false)
        repo.setShowHardwareAlerts(false)
        repo.setShowInstallMixupAlerts(false)
        repo.setNotificationEnabled(false)
        repo.setQuickControlsEnabled(false)
        val presetId = scratchPreset("Summary Preset")
        repo.selectPreset(presetId)
        await("the summary preset to be active") { repo.activePreset.value.id == presetId }

        step(OnboardingStep.DONE)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Summary").assertExists()
        composeRule.onNodeWithText("Light").assertExists()
        composeRule.onNodeWithText("Summary Preset").assertExists()
        // Alerts + controls notification both read "off"; the tile reads "disabled".
        composeRule.onAllNodesWithText("off").assertCountEquals(2)
        composeRule.onNodeWithText("disabled").assertExists()
        composeRule.onAllNodesWithText("on").assertCountEquals(0)
        composeRule.onAllNodesWithText("enabled").assertCountEquals(0)
    }

    @Test
    fun `the summary reports the settings that are actually on`() {
        repo.setThemeMode(ThemeMode.DARK)
        repo.setShowInstallAlerts(true)
        repo.setShowBridgeAlerts(false)
        repo.setShowHardwareAlerts(false)
        repo.setShowInstallMixupAlerts(false)
        repo.setNotificationEnabled(true)
        repo.setQuickControlsEnabled(true)
        // Own the active preset too: its name lands in the summary, and an inherited one could
        // collide with the exact-text assertions below.
        val presetId = scratchPreset("Summary Preset Two")
        repo.selectPreset(presetId)
        await("the summary preset to be active") { repo.activePreset.value.id == presetId }

        step(OnboardingStep.DONE)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Dark").assertExists()
        // A single advisory category still counts as "on" for the summary line.
        composeRule.onAllNodesWithText("on").assertCountEquals(2)
        composeRule.onNodeWithText("enabled").assertExists()
        composeRule.onAllNodesWithText("off").assertCountEquals(0)
        composeRule.onAllNodesWithText("disabled").assertCountEquals(0)
    }
}
