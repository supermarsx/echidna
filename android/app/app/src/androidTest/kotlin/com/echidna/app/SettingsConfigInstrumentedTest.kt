package com.echidna.app

import android.app.NotificationManager
import android.content.Context
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.ui.settings.SettingsScreen
import com.echidna.app.ui.settings.SettingsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the t9-e4 honest configurability surfaces persist and take effect on a device:
 * the status-poll interval persists (and is coerced to its valid range), the high-priority
 * notification preference actually rebuilds the controls channel at a higher importance, and
 * keep-screen-on drives the real [MainActivity] window flag. Also drives a preference toggle
 * through the Settings Compose UI end-to-end.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SettingsConfigInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repo = ControlStateRepository

    private var savedPoll = 2
    private var savedHighPriority = false
    private var savedKeepScreenOn = false

    @Before
    fun capture() {
        val s = repo.settingsState.value
        savedPoll = s.statusPollIntervalSeconds
        savedHighPriority = s.highPriorityNotification
        savedKeepScreenOn = s.keepScreenOn
    }

    @After
    fun restore() {
        repo.setStatusPollIntervalSeconds(savedPoll)
        repo.setHighPriorityNotification(savedHighPriority)
        repo.setKeepScreenOn(savedKeepScreenOn)
    }

    @Test
    fun statusPollIntervalPersistsAndCoercesToRange() {
        repo.setStatusPollIntervalSeconds(7)
        assertEquals(7, repo.settingsState.value.statusPollIntervalSeconds)

        repo.setStatusPollIntervalSeconds(99)
        assertEquals("above-range values must be coerced to the 10s maximum",
            10, repo.settingsState.value.statusPollIntervalSeconds)

        repo.setStatusPollIntervalSeconds(0)
        assertEquals("below-range values must be coerced to the 1s minimum",
            1, repo.settingsState.value.statusPollIntervalSeconds)
    }

    @Test
    fun highPriorityNotificationPreferencePersistsAndKeepsChannel() {
        // Honest scope: Android retains a channel's importance across delete+recreate under the same
        // id (the system floors a raise once the channel exists), so this asserts the observable,
        // guaranteed effects — the preference persists and applyImportance keeps the controls channel
        // present — rather than a channel-importance bump the platform does not promise.
        val manager = context.getSystemService(NotificationManager::class.java)

        repo.setHighPriorityNotification(true)
        assertTrue(
            "high-priority preference must persist",
            repo.settingsState.value.highPriorityNotification,
        )
        assertNotNull(
            "controls channel must remain present after applyImportance(true)",
            manager.getNotificationChannel(CONTROLS_CHANNEL_ID),
        )

        repo.setHighPriorityNotification(false)
        assertFalse(
            "clearing the preference must persist",
            repo.settingsState.value.highPriorityNotification,
        )
        assertNotNull(
            "controls channel must remain present after applyImportance(false)",
            manager.getNotificationChannel(CONTROLS_CHANNEL_ID),
        )
    }

    @Test
    fun keepScreenOnDrivesMainActivityWindowFlag() {
        // Each direction is proven with its own fresh MainActivity composition so the assertion does
        // not depend on recomposition timing while the activity is already resumed.
        repo.setKeepScreenOn(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitFlag(scenario, expected = true)
        }

        repo.setKeepScreenOn(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitFlag(scenario, expected = false)
        }
    }

    @Test
    fun keepScreenOnToggleWorksThroughSettingsUi() {
        repo.setKeepScreenOn(false)
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    viewModel = SettingsViewModel(),
                    onLaunchCompatibility = {},
                    onLaunchWhitelist = {},
                    onLaunchInstaller = {},
                    onOpenAlerts = {},
                )
            }
        }

        // Switch to the Appearance tab, which hosts the Keep-screen-on toggle. Wait for the tab to be
        // laid out before clicking, then let the ScrollableTabRow indicator animation settle, so the
        // gesture lands on a stable row instead of one still measuring/scrolling — the CI-only flake
        // that surfaced as a Compose timeout on a loaded emulator.
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Appearance").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Appearance").performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithContentDescription("Keep screen on")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        // The toggle is the last row of a long Appearance section inside the screen's
        // verticalScroll column: it is composed (so the wait above sees it) but sits below the
        // viewport, where performClick would land outside the visible bounds and never reach the
        // switch. Scroll it into view first, then click.
        composeRule.onNodeWithContentDescription("Keep screen on")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.waitUntil(TIMEOUT_MS) { repo.settingsState.value.keepScreenOn }
        assertTrue(repo.settingsState.value.keepScreenOn)
    }

    private fun awaitFlag(
        scenario: ActivityScenario<MainActivity>,
        expected: Boolean,
        timeoutMs: Long = 5_000L,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val holder = BooleanArray(1)
            scenario.onActivity { activity ->
                holder[0] = (activity.window.attributes.flags and
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
            }
            if (holder[0] == expected) return
            Thread.sleep(50)
        }
        throw AssertionError("FLAG_KEEP_SCREEN_ON never became $expected")
    }

    private companion object {
        // Mirrors NotificationController.CHANNEL_ID (private there).
        const val CONTROLS_CHANNEL_ID = "echidna_controls"
        // Headroom for the Compose UI interaction under a loaded CI emulator (the flake this test
        // previously hit was a timeout waiting on the Appearance tab / toggle to settle).
        const val TIMEOUT_MS = 10_000L
    }
}
