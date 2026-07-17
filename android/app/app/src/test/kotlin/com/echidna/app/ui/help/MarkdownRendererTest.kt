package com.echidna.app.ui.help

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric + Compose coverage for the native Markdown renderer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MarkdownRendererTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `headings, paragraphs, and code render their text`() {
        val model = MarkdownParser.parse("# Architecture\n\nThe DSP engine runs natively.\n\n```\ndlopen(engine)\n```\n")
        composeRule.setContent {
            MaterialTheme { MarkdownBlocks(blocks = model.blocks, onLinkClick = {}) }
        }
        composeRule.onNodeWithText("Architecture", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("DSP engine runs natively", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("dlopen(engine)", substring = true).assertIsDisplayed()
    }

    @Test
    fun `link text renders`() {
        // The link destination routing is unit-tested in HelpLinksTest and the tap gesture is
        // exercised in the instrumentation test (Robolectric has no real input pipeline for the
        // ClickableText pointer gesture). Here we assert the link renders its label.
        val model = MarkdownParser.parse("See [Open Verification](verification.md#anchor) for more.")
        composeRule.setContent {
            MaterialTheme { MarkdownBlocks(blocks = model.blocks, onLinkClick = {}) }
        }
        composeRule.onNodeWithText("Open Verification", substring = true).assertIsDisplayed()
    }

    @Test
    fun `bullet list items render`() {
        val model = MarkdownParser.parse("- alpha item\n- beta item\n")
        composeRule.setContent {
            MaterialTheme { MarkdownBlocks(blocks = model.blocks, onLinkClick = {}) }
        }
        composeRule.onNodeWithText("alpha item", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("beta item", substring = true).assertIsDisplayed()
    }

    @Test
    fun `renderer tolerates a highlight term without dropping text`() {
        val model = MarkdownParser.parse("A note about the bootloop watchdog.")
        composeRule.setContent {
            MaterialTheme { MarkdownBlocks(blocks = model.blocks, onLinkClick = {}, highlight = "bootloop") }
        }
        composeRule.onNodeWithText("bootloop watchdog", substring = true).assertIsDisplayed()
    }
}
