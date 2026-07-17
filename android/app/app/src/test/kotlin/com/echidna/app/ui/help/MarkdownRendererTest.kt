package com.echidna.app.ui.help

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeTrue
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

    @Test
    fun `a bundled PNG renders as an image with its alt as the content description`() {
        // Use whatever screenshot stageHelpDocs actually bundled, so the test tracks the real assets
        // without pinning a specific filename (S1 owns/renames those). Skipped only if none staged.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val screenshots = context.assets
            .list("${HelpRepository.ASSET_ROOT}/assets/screenshots")?.toList().orEmpty()
        val png = screenshots.firstOrNull { it.endsWith(".png", ignoreCase = true) }
        assumeTrue("no staged screenshot PNG to exercise the image renderer", png != null)

        val model = MarkdownParser.parse("![A staged screenshot](assets/screenshots/$png)")
        composeRule.setContent {
            MaterialTheme {
                MarkdownBlocks(blocks = model.blocks, onLinkClick = {}, docId = "screenshots.md")
            }
        }
        // The decoded image is present (content description = alt) and did NOT fall back to a caption.
        composeRule.onNodeWithContentDescription("A staged screenshot").assertIsDisplayed()
        composeRule.onNodeWithText("Image unavailable").assertDoesNotExist()
    }

    @Test
    fun `a missing asset degrades to a labeled caption, not raw markup`() {
        val model = MarkdownParser.parse("![Onboarding welcome](assets/screenshots/does-not-exist.png)")
        composeRule.setContent {
            MaterialTheme {
                MarkdownBlocks(blocks = model.blocks, onLinkClick = {}, docId = "getting-started.md")
            }
        }
        composeRule.onNodeWithText("Image unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Onboarding welcome", substring = true).assertIsDisplayed()
        // No stray markdown source leaks through.
        composeRule.onNodeWithText("![", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a remote image URL degrades to a web-docs caption`() {
        val model = MarkdownParser.parse("![Hosted banner](https://example.com/banner.png)")
        composeRule.setContent {
            MaterialTheme { MarkdownBlocks(blocks = model.blocks, onLinkClick = {}, docId = "index.md") }
        }
        composeRule.onNodeWithText("view in the web docs", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Hosted banner", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an SVG degrades to a vector-image caption`() {
        val model = MarkdownParser.parse("![Pipeline vector](assets/diagrams/pipeline.svg)")
        composeRule.setContent {
            MaterialTheme { MarkdownBlocks(blocks = model.blocks, onLinkClick = {}, docId = "architecture.md") }
        }
        composeRule.onNodeWithText("Vector image", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a mermaid fence degrades to a diagram caption instead of raw source`() {
        val model = MarkdownParser.parse("```mermaid\ngraph TD; A-->B;\n```\n")
        composeRule.setContent {
            MaterialTheme { MarkdownBlocks(blocks = model.blocks, onLinkClick = {}) }
        }
        composeRule.onNodeWithText("Diagram — view in the web docs", substring = true).assertIsDisplayed()
        // The raw mermaid source is not dumped into a code block.
        composeRule.onNodeWithText("graph TD", substring = true).assertDoesNotExist()
    }
}
