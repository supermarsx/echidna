package com.echidna.app.ui.install

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.echidna.app.data.DismissedAlertsStore
import com.echidna.app.ui.components.AlertTestTags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioural tests for the install screen's root-module [RiskCard] after it was converted to a
 * dismissible alert:
 *  - it renders as a dismissible alert with both the plain and permanent affordances,
 *  - a permanent "Don't remind" persists and never reappears, and
 *  - a plain dismiss is scoped to the install state, so a material change (module installed <->
 *    not installed) surfaces the safety warning once more rather than silencing it forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InstallEngineRiskCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        context.getSharedPreferences(DismissedAlertsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `risk card renders as a dismissible alert with permanent affordance`() {
        composeRule.setContent {
            MaterialTheme { RiskCard(moduleInstalled = false) }
        }
        composeRule.onNodeWithTag(AlertTestTags.CARD).assertExists()
        composeRule.onNodeWithTag(AlertTestTags.DISMISS).assertExists()
        composeRule.onNodeWithTag(AlertTestTags.PERMANENT_DISMISS).assertExists()
    }

    @Test
    fun `permanent dismiss hides the card and never reappears`() {
        composeRule.setContent {
            MaterialTheme { RiskCard(moduleInstalled = false) }
        }
        composeRule.onNodeWithTag(AlertTestTags.PERMANENT_DISMISS).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(AlertTestTags.CARD).assertDoesNotExist()
        // Recorded as permanent under the current-state key, so reconcile can never bring it back.
        assertTrue(
            DismissedAlertsStore(context).isPermanentlyDismissed("install.risk:not_installed")
        )
    }

    @Test
    fun `plain dismiss returns when the install state materially changes`() {
        var installed by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme { RiskCard(moduleInstalled = installed) }
        }

        // Temporary dismiss while the module is not installed.
        composeRule.onNodeWithTag(AlertTestTags.DISMISS).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(AlertTestTags.CARD).assertDoesNotExist()
        assertFalse(
            DismissedAlertsStore(context).isPermanentlyDismissed("install.risk:not_installed")
        )

        // Module becomes installed → a new state key → the safety warning surfaces once more.
        composeRule.runOnIdle { installed = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(AlertTestTags.CARD).assertExists()
    }
}
