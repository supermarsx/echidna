package com.echidna.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.echidna.app.data.DismissedAlertsStore
import com.echidna.app.ui.AppDestination
import com.echidna.app.ui.AppNavGraph
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real navigation into the Install Engine screen through the app's actual [AppNavGraph], driven by
 * clicks — one path from the Dashboard's install-risk CTA, one from the Settings → Engine entry.
 * Proves the two documented entry points are wired to the installer destination and that the
 * destination composes on arrival.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class InstallEngineNavigationInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun clearDismissals() {
        // The dashboard install-risk CTA lives inside a permanently-dismissible alert; make sure a
        // prior run's "Don't remind" doesn't hide the entry point under test.
        DismissedAlertsStore(ApplicationProvider.getApplicationContext<android.content.Context>()).clear()
    }

    @Test
    fun dashboardCtaNavigatesToInstallScreen() {
        composeRule.setContent {
            MaterialTheme {
                val navController = rememberNavController()
                NavHost(navController, startDestination = AppDestination.Dashboard.route) {
                    AppNavGraph(navController)
                }
            }
        }

        composeRule.onNodeWithText("Set up / install engine").performClick()

        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Device status").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Device status").assertExists()
    }

    @Test
    fun settingsEngineEntryNavigatesToInstallScreen() {
        composeRule.setContent {
            MaterialTheme {
                val navController = rememberNavController()
                NavHost(navController, startDestination = AppDestination.Settings.route) {
                    AppNavGraph(navController)
                }
            }
        }

        // Settings opens on the Alerts tab; switch to Engine, then use its installer entry point.
        composeRule.onNodeWithText("Engine").performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Install engine").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Install engine").performClick()

        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Device status").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Device status").assertExists()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
