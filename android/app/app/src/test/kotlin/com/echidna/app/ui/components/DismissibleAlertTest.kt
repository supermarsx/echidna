package com.echidna.app.ui.components

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.echidna.app.data.DismissedAlertsStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioural tests for the reusable alert component under Robolectric + Compose:
 *  - dismiss hides the banner and persists the dismissed state,
 *  - the action button renders and invokes onAction when both label + callback are supplied,
 *  - a dismiss-only alert renders without an action button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DismissibleAlertTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        context.getSharedPreferences(DismissedAlertsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `dismiss-only alert renders without an action button`() {
        composeRule.setContent {
            MaterialTheme {
                DismissibleAlert(
                    message = "Info only",
                    severity = AlertSeverity.INFO,
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag(AlertTestTags.CARD).assertExists()
        composeRule.onNodeWithTag(AlertTestTags.DISMISS).assertExists()
        composeRule.onNodeWithTag(AlertTestTags.ACTION).assertDoesNotExist()
    }

    @Test
    fun `action button renders and invokes onAction`() {
        var actionInvoked = false
        composeRule.setContent {
            MaterialTheme {
                DismissibleAlert(
                    title = "Needs attention",
                    message = "Do the thing",
                    severity = AlertSeverity.WARNING,
                    onDismiss = {},
                    actionLabel = "Fix it",
                    onAction = { actionInvoked = true },
                )
            }
        }
        composeRule.onNodeWithTag(AlertTestTags.ACTION).assertExists()
        composeRule.onNodeWithTag(AlertTestTags.ACTION).performClick()
        assertTrue(actionInvoked)
    }

    @Test
    fun `dismiss invokes onDismiss callback`() {
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                DismissibleAlert(
                    message = "Dismiss me",
                    severity = AlertSeverity.ERROR,
                    onDismiss = { dismissed = true },
                )
            }
        }
        composeRule.onNodeWithTag(AlertTestTags.DISMISS).performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `persistent alert hides and persists after dismiss`() {
        val store = DismissedAlertsStore(context)
        composeRule.setContent {
            MaterialTheme {
                PersistentDismissibleAlert(
                    alertKey = "test.key",
                    store = store,
                    message = "Persisted alert",
                    severity = AlertSeverity.WARNING,
                )
            }
        }
        composeRule.onNodeWithTag(AlertTestTags.CARD).assertExists()
        assertFalse(store.isDismissed("test.key"))

        composeRule.onNodeWithTag(AlertTestTags.DISMISS).performClick()
        composeRule.waitForIdle()

        // Hidden from the layout and persisted so it stays gone on the next launch.
        composeRule.onNodeWithTag(AlertTestTags.CARD).assertDoesNotExist()
        assertTrue(store.isDismissed("test.key"))
    }

    @Test
    fun `persistent alert does not render when already dismissed`() {
        val store = DismissedAlertsStore(context)
        store.setDismissed("already.dismissed", true)
        composeRule.setContent {
            MaterialTheme {
                PersistentDismissibleAlert(
                    alertKey = "already.dismissed",
                    store = store,
                    message = "Should not show",
                    severity = AlertSeverity.INFO,
                )
            }
        }
        composeRule.onNodeWithTag(AlertTestTags.CARD).assertDoesNotExist()
    }
}
