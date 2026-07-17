package com.echidna.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.echidna.app.ui.help.HelpTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end Help flow on a device/emulator through the real [MainActivity]: the app-wide top-bar
 * Help action opens the Help screen, the bundled docs are listed, full-text search returns results,
 * and tapping a result opens the doc — all via real input gestures (which Robolectric cannot inject).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class HelpFlowInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun tagCount(tag: String): Int =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun helpActionOpensSearchableDocIndex() {
        // The Help action lives in the app-wide top bar (present on the Dashboard start screen).
        composeRule.onNodeWithContentDescription("Help").performClick()
        composeRule.waitUntil(TIMEOUT_MS) { tagCount(HelpTestTags.DOC_INDEX) > 0 }
        composeRule.onNodeWithTag(HelpTestTags.DOC_INDEX).assertExists()

        // Full-text search over the real bundled docs returns ranked results.
        composeRule.onNodeWithTag(HelpTestTags.SEARCH_FIELD).performTextInput("bootloop")
        composeRule.waitUntil(TIMEOUT_MS) { tagCount(HelpTestTags.RESULTS) > 0 }
        composeRule.onNodeWithTag(HelpTestTags.RESULTS).assertExists()

        // Tapping the top result opens the corresponding doc (real gesture on a LazyColumn item).
        composeRule.onNodeWithTag(HelpTestTags.resultRow(0)).performClick()
        composeRule.waitUntil(TIMEOUT_MS) { tagCount(HelpTestTags.DOC_VIEW) > 0 }
        composeRule.onNodeWithTag(HelpTestTags.DOC_VIEW).assertExists()
        composeRule.onNodeWithTag(HelpTestTags.DOC_TITLE).assertExists()

        // Back returns within Help (to the search results / index) rather than leaving the screen.
        composeRule.onNodeWithTag(HelpTestTags.DOC_BACK).performClick()
        composeRule.waitUntil(TIMEOUT_MS) { tagCount(HelpTestTags.SEARCH_FIELD) > 0 }
        composeRule.onNodeWithTag(HelpTestTags.SEARCH_FIELD).assertExists()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
