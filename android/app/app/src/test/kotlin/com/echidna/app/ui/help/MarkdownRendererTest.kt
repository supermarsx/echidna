package com.echidna.app.ui.help

import androidx.compose.foundation.lazy.rememberLazyListState
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

    @Test
    fun `an ordered list renders its own numbering from the declared start`() {
        val model = MarkdownParser.parse("3. flash the module\n4. reboot the device\n")
        render(model)

        // The marker must come from the parsed start, not from a hard-coded "1.".
        composeRule.onNodeWithText("3.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("4.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("flash the module", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("reboot the device", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a blockquote renders its content without the marker`() {
        val model = MarkdownParser.parse("> Never run this on a locked bootloader.\n")
        render(model)

        composeRule.onNodeWithText("Never run this on a locked bootloader.", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(">", substring = true).assertDoesNotExist()
    }

    @Test
    fun `an admonition renders its custom title and nested body`() {
        val model = MarkdownParser.parse(
            "!!! danger \"Bootloop risk\"\n    Removing the module mid-boot can brick the device.\n"
        )
        render(model)

        composeRule.onNodeWithText("Bootloop risk").assertIsDisplayed()
        composeRule.onNodeWithText("brick the device", substring = true).assertIsDisplayed()
        // The MkDocs marker itself must never leak into the rendered Help.
        composeRule.onNodeWithText("!!!", substring = true).assertDoesNotExist()
    }

    @Test
    fun `an untitled admonition falls back to its capitalised kind`() {
        val model = MarkdownParser.parse("!!! warning\n    Latency may rise on this HAL.\n")
        render(model)

        composeRule.onNodeWithText("Warning").assertIsDisplayed()
        composeRule.onNodeWithText("Latency may rise", substring = true).assertIsDisplayed()
    }

    @Test
    fun `every admonition kind renders rather than dropping unrecognised ones`() {
        // The accent colour differs per family; what must never happen is a kind rendering nothing.
        val model = MarkdownParser.parse(
            """
            !!! tip
                success family

            !!! attention
                warning family

            !!! bug
                error family

            !!! note
                default family
            """.trimIndent()
        )
        render(model)

        composeRule.onNodeWithText("success family", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("warning family", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("error family", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("default family", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a table renders headers and every cell, padding short rows`() {
        val model = MarkdownParser.parse(
            """
            | Mode | Owner |
            | --- | --- |
            | Native first | Zygisk |
            | Compatibility |
            """.trimIndent() + "\n"
        )
        render(model)

        composeRule.onNodeWithText("Mode", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Owner", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Native first", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Zygisk", substring = true).assertIsDisplayed()
        // A row with fewer cells than the header must still render, not crash the doc.
        composeRule.onNodeWithText("Compatibility", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a horizontal rule renders as a separator without emitting stray text`() {
        val model = MarkdownParser.parse("before\n\n---\n\nafter\n")
        render(model)

        composeRule.onNodeWithText("before", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("after", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("---", substring = true).assertDoesNotExist()
    }

    @Test
    fun `bold, italic, and code spans keep their text and drop their markers`() {
        val model = MarkdownParser.parse("A **bold** and *slanted* run with `inline_code` inside.")
        render(model)

        composeRule.onNodeWithText("bold", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("slanted", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("inline_code", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("**", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("`", substring = true).assertDoesNotExist()
    }

    @Test
    fun `an image mixed into prose degrades to its alt text rather than raw markup`() {
        val model = MarkdownParser.parse("Tap the ![gear glyph](assets/gear.png) to open settings.")
        render(model, docId = "getting-started.md")

        composeRule.onNodeWithText("gear glyph", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("assets/gear.png", substring = true).assertDoesNotExist()
        // Inline images are never promoted to the block image renderer's caption card.
        composeRule.onNodeWithText("Image unavailable").assertDoesNotExist()
    }

    @Test
    fun `an image without a doc id still resolves against the docs root`() {
        val model = MarkdownParser.parse("![Rootless banner](does-not-exist.png)")
        render(model, docId = null)

        composeRule.onNodeWithText("Image unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Rootless banner", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an image destination that resolves to nothing degrades instead of throwing`() {
        val model = MarkdownParser.parse("![Anchor only](#section)")
        render(model, docId = "index.md")

        composeRule.onNodeWithText("Image unavailable").assertIsDisplayed()
    }

    @Test
    fun `highlighting marks each term without losing surrounding or unmatched text`() {
        val model = MarkdownParser.parse(
            "The zygisk bridge and the lsposed shim both fail closed when policy is absent."
        )
        composeRule.setContent {
            MaterialTheme {
                MarkdownBlocks(
                    blocks = model.blocks,
                    onLinkClick = {},
                    highlight = "zygisk lsposed",
                )
            }
        }

        // Both terms plus the text before, between, and after them must survive the span splitting.
        composeRule.onNodeWithText("The zygisk bridge and the lsposed shim", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("fail closed when policy is absent", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `a highlight term that never matches leaves the text intact`() {
        val model = MarkdownParser.parse("Nothing in this sentence matches.")
        composeRule.setContent {
            MaterialTheme {
                MarkdownBlocks(blocks = model.blocks, onLinkClick = {}, highlight = "selinux")
            }
        }

        composeRule.onNodeWithText("Nothing in this sentence matches.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `MarkdownDocument renders the scrollable form and can start at a heading anchor`() {
        val model = MarkdownParser.parse("# Top\n\nintro text\n\n## Verification\n\nproof text\n")
        val anchorIndex = model.blockIndexForAnchor("verification")
        composeRule.setContent {
            MaterialTheme {
                MarkdownDocument(
                    model = model,
                    onLinkClick = {},
                    listState = rememberLazyListState(
                        initialFirstVisibleItemIndex = anchorIndex ?: 0,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Verification", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("proof text", substring = true).assertIsDisplayed()
    }

    private fun render(model: MarkdownDocumentModel, docId: String? = null) {
        composeRule.setContent {
            MaterialTheme { MarkdownBlocks(blocks = model.blocks, onLinkClick = {}, docId = docId) }
        }
    }
}
