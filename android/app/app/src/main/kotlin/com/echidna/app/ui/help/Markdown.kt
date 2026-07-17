package com.echidna.app.ui.help

/**
 * Pure, Android-free data model for a parsed Markdown document.
 *
 * The [MarkdownParser] produces this tree and the Compose [MarkdownDocument] renderer consumes it.
 * Keeping the model and parser free of Android dependencies makes them straightforward to unit-test
 * on the JVM (no Robolectric needed), which is where the bulk of the Help correctness coverage lives.
 */

/** An inline span inside a block (a run of text, emphasis, a code span, or a link). */
sealed interface MarkdownInline {
    /** Literal text. */
    data class Text(val text: String) : MarkdownInline

    /** `**bold**` / `__bold__`. */
    data class Bold(val children: List<MarkdownInline>) : MarkdownInline

    /** `*italic*` / `_italic_`. */
    data class Italic(val children: List<MarkdownInline>) : MarkdownInline

    /** `` `code` `` span (never re-parsed for markers). */
    data class Code(val text: String) : MarkdownInline

    /**
     * `[text](destination)` or a resolved reference link. [destination] is the raw target as written
     * in the source (e.g. `verification.md#anchor`, `https://example.com`); link classification and
     * resolution happen at render time via [HelpLinks].
     */
    data class Link(val children: List<MarkdownInline>, val destination: String) : MarkdownInline

    /**
     * `![alt](destination)` sitting *inside* a paragraph (i.e. alongside other text). A lone image on
     * its own line is promoted to the block-level [MarkdownBlock.Image] instead; this inline variant
     * covers the rarer case of an image mixed with surrounding prose, where it degrades to its [alt].
     * [destination] is the raw target as written; resolution happens at render time.
     */
    data class Image(val alt: String, val destination: String) : MarkdownInline
}

/** One item of a bullet or ordered list. */
data class MarkdownListItem(val inlines: List<MarkdownInline>)

/** A block-level Markdown node. */
sealed interface MarkdownBlock {
    /**
     * An ATX/setext heading. [anchor] is the GitHub-style slug used for in-doc links and
     * scroll-to-section; [text] is the flattened plain text (used by the search index and for
     * section labels).
     */
    data class Heading(
        val level: Int,
        val inlines: List<MarkdownInline>,
        val anchor: String,
        val text: String,
    ) : MarkdownBlock

    data class Paragraph(val inlines: List<MarkdownInline>) : MarkdownBlock

    /** A fenced or indented code block. [language] is the fence info string when present. */
    data class CodeBlock(val code: String, val language: String?) : MarkdownBlock

    data class BulletList(val items: List<MarkdownListItem>) : MarkdownBlock

    data class OrderedList(val start: Int, val items: List<MarkdownListItem>) : MarkdownBlock

    /** A `>` blockquote, flattened to its inline content. */
    data class Quote(val inlines: List<MarkdownInline>) : MarkdownBlock

    /** A pipe table: a header row plus zero or more body rows, each cell a list of inlines. */
    data class Table(
        val headers: List<List<MarkdownInline>>,
        val rows: List<List<List<MarkdownInline>>>,
    ) : MarkdownBlock

    /**
     * An MkDocs-style admonition (`!!! warning "Title"`). [kind] is the admonition keyword
     * (warning/danger/note/…), [title] the optional custom title, [children] the nested blocks.
     * The repo docs lean on these heavily (see docs/index.md), so rendering them as real callouts
     * rather than stray `!!!` paragraphs keeps the in-app Help faithful.
     */
    data class Admonition(
        val kind: String,
        val title: String?,
        val children: List<MarkdownBlock>,
    ) : MarkdownBlock

    /**
     * A block-level image: `![alt](destination)` alone on its line. [destination] is the raw target as
     * written in the source (relative to the doc, e.g. `assets/screenshots/14-help-tab.png`, or a
     * remote URL). The renderer resolves it against the bundled Help assets and decodes PNG/WebP
     * inline; SVG, remote `http(s)`, and missing/undecodable assets degrade to a captioned
     * placeholder. [alt] doubles as the caption / content description.
     */
    data class Image(val alt: String, val destination: String) : MarkdownBlock

    object HorizontalRule : MarkdownBlock
}

/** A fully parsed document: its blocks plus a fast heading-anchor → block-index lookup. */
data class MarkdownDocumentModel(
    val blocks: List<MarkdownBlock>,
) {
    /** Zero-based index of the block carrying [anchor], or null when no heading has that slug. */
    fun blockIndexForAnchor(anchor: String): Int? =
        blocks.indexOfFirst { it is MarkdownBlock.Heading && it.anchor == anchor }
            .takeIf { it >= 0 }
}

/** Flattens an inline tree to plain text (for slugs, search text, and content descriptions). */
fun List<MarkdownInline>.plainText(): String = buildString {
    fun walk(nodes: List<MarkdownInline>) {
        for (node in nodes) when (node) {
            is MarkdownInline.Text -> append(node.text)
            is MarkdownInline.Code -> append(node.text)
            is MarkdownInline.Bold -> walk(node.children)
            is MarkdownInline.Italic -> walk(node.children)
            is MarkdownInline.Link -> walk(node.children)
            is MarkdownInline.Image -> append(node.alt)
        }
    }
    walk(this@plainText)
}

/**
 * GitHub-style heading slug: lowercased, spaces to hyphens, punctuation stripped. Used so in-doc
 * `#anchor` links and search-result section targets line up with rendered headings.
 */
fun slugify(text: String): String =
    text.trim()
        .lowercase()
        .replace(Regex("[^a-z0-9 \\-_]"), "")
        .replace(Regex("\\s+"), "-")
        .replace(Regex("-+"), "-")
        .trim('-')
