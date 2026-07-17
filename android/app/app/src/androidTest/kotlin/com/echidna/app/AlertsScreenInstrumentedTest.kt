package com.echidna.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.data.DismissedAlertsStore
import com.echidna.app.ui.alerts.AlertsScreen
import com.echidna.app.ui.alerts.AlertsViewModel
import com.echidna.app.ui.components.AlertSeverity
import com.echidna.app.ui.components.AlertTestTags
import com.echidna.app.ui.components.PersistentDismissibleAlert
import com.echidna.app.ui.components.rememberDismissedAlertsStore
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the top-level Alerts tab against the real [AlertsViewModel]/repository.
 *
 * The repository is nudged into a state that deterministically produces the "no whitelisted apps"
 * advisory (master on, not bypassed, whitelist emptied), so the test can prove: advisories render,
 * an action button routes to the correct destination, and a permanent "Don't remind" dismissal is
 * durably persisted so a relaunched activity keeps the banner hidden.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AlertsScreenInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = DismissedAlertsStore(context)

    private var savedMaster = true
    private var savedBypass = false
    private val clearedKeys = mutableListOf<String>()

    @Before
    fun forceWhitelistAdvisory() {
        val repo = ControlStateRepository
        savedMaster = repo.masterEnabled.value
        savedBypass = repo.bypass.value
        // Clear any prior permanent dismissals so the advisory under test is allowed to appear.
        store.clear()
        repo.setMasterEnabled(true)
        repo.setBypass(false)
        // Empty the whitelist so enabledCount()==0 → the "No target apps are whitelisted" advisory.
        repo.whitelistBindings.value.whitelist.keys.toList().forEach { pkg ->
            repo.updateWhitelist(pkg, false)
        }
    }

    @After
    fun restore() {
        val repo = ControlStateRepository
        repo.setMasterEnabled(savedMaster)
        repo.setBypass(savedBypass)
        clearedKeys.forEach { store.setPermanentlyDismissed(it, false) }
        store.clear()
    }

    @Test
    fun whitelistAdvisoryRendersAndRoutesToWhitelist() {
        val routedToWhitelist = AtomicBoolean(false)
        composeRule.setContent {
            MaterialTheme {
                AlertsScreen(
                    viewModel = AlertsViewModel(),
                    onOpenInstall = {},
                    onLaunchWhitelist = { routedToWhitelist.set(true) },
                    onLaunchCompatibility = {},
                )
            }
        }

        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText("No target apps are whitelisted")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("No target apps are whitelisted").performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithText("Open Whitelist").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertTrue("Open Whitelist action must route to the whitelist", routedToWhitelist.get())
        }
    }

    @Test
    fun permanentDismissHidesBannerAndPersistsDurably() {
        val key = "alerts.advisory:test|persistent-dismiss"
        clearedKeys += key
        composeRule.setContent {
            MaterialTheme {
                PersistentDismissibleAlert(
                    alertKey = key,
                    store = rememberDismissedAlertsStore(),
                    title = "Persistent test advisory",
                    message = "Should not return after Don't remind.",
                    severity = AlertSeverity.WARNING,
                )
            }
        }

        composeRule.onNodeWithText("Persistent test advisory").assertIsDisplayed()
        composeRule.onNodeWithTag(AlertTestTags.PERMANENT_DISMISS).performClick()
        composeRule.onNodeWithText("Persistent test advisory").assertDoesNotExist()

        // A brand-new store instance reads the same durable SharedPreferences a relaunched activity
        // would — the "Don't remind" choice survives process/activity recreation.
        assertTrue(DismissedAlertsStore(context).isPermanentlyDismissed(key))
    }

    @Test
    fun previouslyDismissedAlertStaysHiddenOnFreshComposition() {
        val key = "alerts.advisory:test|already-dismissed"
        clearedKeys += key
        store.setPermanentlyDismissed(key, true)

        composeRule.setContent {
            MaterialTheme {
                PersistentDismissibleAlert(
                    alertKey = key,
                    store = rememberDismissedAlertsStore(),
                    title = "Already dismissed advisory",
                    message = "Was permanently dismissed before this composition.",
                    severity = AlertSeverity.WARNING,
                )
            }
        }

        // A relaunched activity re-reads the store during composition and must never surface it.
        composeRule.onNodeWithText("Already dismissed advisory").assertDoesNotExist()
        assertFalse(
            "sanity: unrelated key is not dismissed",
            store.isPermanentlyDismissed("alerts.advisory:test|unrelated"),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
