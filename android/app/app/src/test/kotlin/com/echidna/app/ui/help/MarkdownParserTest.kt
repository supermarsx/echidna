package com.echidna.app.ui.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM coverage for the self-contained Markdown parser: headings, lists, fenced code, inline
 * emphasis/code, inline + reference links, tables, blockquotes, admonitions, and slug generation.
 */
class MarkdownParserTest {

    private fun parse(src: String) = MarkdownParser.parse(src).blocks

    @Test
    fun `atx headings capture level, text, and github-style anchor`() {
        val blocks = parse("# Build & Install\n\n## Getting Started\n")
        val h1 = blocks[0] as MarkdownBlock.Heading
        val h2 = blocks[1] as MarkdownBlock.Heading
        assertEquals(1, h1.level)
        assertEquals("Build & Install", h1.text)
        assertEquals("build-install", h1.anchor)
        assertEquals(2, h2.level)
        assertEquals("getting-started", h2.anchor)
    }

    @Test
    fun `slugify strips punctuation and collapses whitespace`() {
        assertEquals("hooking-audioflinger-hard", slugify("Hooking AudioFlinger (Hard!)"))
        assertEquals("section-15-recovery", slugify("Section 15: Recovery"))
    }

    @Test
    fun `paragraph folds soft-wrapped lines and parses inline emphasis`() {
        val blocks = parse("This is **bold** and *italic* and `code`\nwrapped onto two lines.")
        val para = blocks.single() as MarkdownBlock.Paragraph
        val kinds = para.inlines.map { it::class.simpleName }
        assertTrue(kinds.contains("Bold"))
        assertTrue(kinds.contains("Italic"))
        assertTrue(kinds.contains("Code"))
        // Soft wrap joined with a space.
        assertTrue(para.inlines.plainText().contains("code wrapped onto"))
    }

    @Test
    fun `bullet list collects items`() {
        val blocks = parse("- one\n- two\n- three\n")
        val list = blocks.single() as MarkdownBlock.BulletList
        assertEquals(3, list.items.size)
        assertEquals("one", list.items[0].inlines.plainText())
    }

    @Test
    fun `ordered list preserves start index`() {
        val blocks = parse("3. third\n4. fourth\n")
        val list = blocks.single() as MarkdownBlock.OrderedList
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
    }

    @Test
    fun `fenced code block captures language and raw body without parsing markers`() {
        val blocks = parse("```bash\n./gradlew build   # not *italic*\n```\n")
        val code = blocks.single() as MarkdownBlock.CodeBlock
        assertEquals("bash", code.language)
        assertTrue(code.code.contains("./gradlew build"))
        assertTrue(code.code.contains("*italic*")) // markers untouched inside code
    }

    @Test
    fun `inline link parses text and destination`() {
        val blocks = parse("See [Verification](verification.md#anchor) for details.")
        val link = (blocks.single() as MarkdownBlock.Paragraph).inlines
            .filterIsInstance<MarkdownInline.Link>().single()
        assertEquals("verification.md#anchor", link.destination)
        assertEquals("Verification", link.children.plainText())
    }

    @Test
    fun `reference-style links resolve against link definitions`() {
        val src = """
            The spec lives in [spec sections 3-4][spec-3-4].

            [spec-3-4]: ../spec.md#sections-3-4
        """.trimIndent()
        val link = (parse(src)[0] as MarkdownBlock.Paragraph).inlines
            .filterIsInstance<MarkdownInline.Link>().single()
        assertEquals("../spec.md#sections-3-4", link.destination)
        assertEquals("spec sections 3-4", link.children.plainText())
    }

    @Test
    fun `pipe table parses headers and rows`() {
        val src = """
            | Route | Status |
            | --- | --- |
            | AudioFlinger | unsupported |
            | Zygisk | supported |
        """.trimIndent()
        val table = parse(src).single() as MarkdownBlock.Table
        assertEquals(2, table.headers.size)
        assertEquals("Route", table.headers[0].plainText())
        assertEquals(2, table.rows.size)
        assertEquals("unsupported", table.rows[0][1].plainText())
    }

    @Test
    fun `blockquote flattens to inline content`() {
        val blocks = parse("> a quoted note\n> spanning lines\n")
        val quote = blocks.single() as MarkdownBlock.Quote
        assertTrue(quote.inlines.plainText().contains("quoted note"))
    }

    @Test
    fun `mkdocs admonition captures kind, title, and indented body`() {
        val src = "!!! warning \"Read first\"\n    Misuse can leave a phone boot-looping.\n\n    A second paragraph.\n"
        val adm = parse(src).single() as MarkdownBlock.Admonition
        assertEquals("warning", adm.kind)
        assertEquals("Read first", adm.title)
        assertTrue(adm.children.any { it is MarkdownBlock.Paragraph })
        assertTrue(adm.children.filterIsInstance<MarkdownBlock.Paragraph>()
            .any { it.inlines.plainText().contains("boot-looping") })
    }

    @Test
    fun `horizontal rule is recognized`() {
        val blocks = parse("above\n\n---\n\nbelow\n")
        assertTrue(blocks.any { it is MarkdownBlock.HorizontalRule })
    }

    @Test
    fun `nested emphasis inside bold parses recursively`() {
        val para = parse("**bold with *nested italic* inside**").single() as MarkdownBlock.Paragraph
        val bold = para.inlines.filterIsInstance<MarkdownInline.Bold>().single()
        assertTrue(bold.children.any { it is MarkdownInline.Italic })
    }

    @Test
    fun `escaped markers are treated literally`() {
        val para = parse("a literal \\*asterisk\\* here").single() as MarkdownBlock.Paragraph
        assertEquals("a literal *asterisk* here", para.inlines.plainText())
    }
}
