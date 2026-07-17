package com.echidna.app.ui.help

/**
 * One bundled documentation page. [id] is the relative asset path under `help/docs/`
 * (e.g. `verification.md`, `hardening/checklist.md`); [markdown] is the raw source.
 */
data class HelpDoc(
    val id: String,
    val title: String,
    val group: String,
    val order: Int,
    val markdown: String,
)

/** A heading-delimited section of a doc, the unit the search index ranks and returns. */
data class DocSection(
    val docId: String,
    val docTitle: String,
    val group: String,
    val index: Int,
    val heading: String,
    val anchor: String,
    val level: Int,
    val body: String,
)

/** A ranked search hit: which doc, which section, a snippet, and the score it ranked on. */
data class SearchResult(
    val docId: String,
    val docTitle: String,
    val group: String,
    val sectionHeading: String,
    val sectionAnchor: String,
    val sectionIndex: Int,
    val snippet: String,
    val score: Int,
)

/**
 * Offline full-text search over the bundled docs.
 *
 * The index splits each doc into heading-delimited [DocSection]s at load time; a query returns
 * sections ranked by where the terms hit (heading and doc-title matches outweigh body matches) with
 * all query terms required (AND). Pure Kotlin, so ranking behaviour is unit-tested on the JVM.
 */
class DocSearchIndex(docs: List<HelpDoc>) {

    private data class IndexedSection(
        val section: DocSection,
        val headingLower: String,
        val bodyLower: String,
        val titleLower: String,
    )

    private val sections: List<IndexedSection> = docs.flatMap { sectionsOf(it) }

    /** All sections, in document order — exposed for tests and diagnostics. */
    val allSections: List<DocSection> get() = sections.map { it.section }

    /**
     * Ranked results for [query]. Case-insensitive; multi-word queries require every term to appear
     * in the section (heading, body, or the owning doc's title). Returns at most [limit] results.
     */
    fun search(query: String, limit: Int = 50): List<SearchResult> {
        val terms = tokenize(query)
        if (terms.isEmpty()) return emptyList()

        val results = ArrayList<SearchResult>()
        for (s in sections) {
            var total = 0
            var allMatched = true
            for (term in terms) {
                val headingHits = countOccurrences(s.headingLower, term)
                val titleHits = countOccurrences(s.titleLower, term)
                val bodyHits = countOccurrences(s.bodyLower, term)
                if (headingHits == 0 && titleHits == 0 && bodyHits == 0) {
                    allMatched = false
                    break
                }
                // Heading matches are the strongest signal, then the doc title, then body frequency.
                total += headingHits * HEADING_WEIGHT + titleHits * TITLE_WEIGHT + bodyHits * BODY_WEIGHT
                if (s.headingLower == term) total += EXACT_HEADING_BONUS
            }
            if (!allMatched) continue
            // Slightly favour higher-level (more prominent) sections on ties.
            total += (MAX_LEVEL - s.section.level).coerceAtLeast(0)
            results.add(
                SearchResult(
                    docId = s.section.docId,
                    docTitle = s.section.docTitle,
                    group = s.section.group,
                    sectionHeading = s.section.heading,
                    sectionAnchor = s.section.anchor,
                    sectionIndex = s.section.index,
                    snippet = snippetFor(s, terms.first()),
                    score = total,
                ),
            )
        }
        results.sortWith(
            compareByDescending<SearchResult> { it.score }
                .thenBy { it.docTitle.lowercase() }
                .thenBy { it.sectionIndex },
        )
        return if (results.size > limit) results.subList(0, limit) else results
    }

    private fun snippetFor(s: IndexedSection, term: String): String {
        val body = s.section.body.replace(Regex("\\s+"), " ").trim()
        if (body.isEmpty()) return s.section.heading
        val bodyLower = body.lowercase()
        val at = bodyLower.indexOf(term)
        if (at < 0) return body.take(SNIPPET_LEN).let { if (body.length > SNIPPET_LEN) "$it…" else it }
        val start = (at - SNIPPET_LEN / 2).coerceAtLeast(0)
        val end = (start + SNIPPET_LEN).coerceAtMost(body.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < body.length) "…" else ""
        return prefix + body.substring(start, end).trim() + suffix
    }

    /** Splits [doc] into heading-delimited sections (text before the first heading is the intro). */
    private fun sectionsOf(doc: HelpDoc): List<IndexedSection> {
        val model = MarkdownParser.parse(doc.markdown)
        val out = ArrayList<IndexedSection>()
        var heading = doc.title
        var anchor = ""
        var level = 1
        var isRealHeading = false
        val body = StringBuilder()
        var idx = 0

        fun flush() {
            val text = body.toString().trim()
            body.setLength(0)
            // Drop an empty synthetic intro (no captured body, no real heading yet).
            if (text.isEmpty() && !isRealHeading) return
            val section = DocSection(doc.id, doc.title, doc.group, idx++, heading, anchor, level, text)
            out.add(IndexedSection(section, heading.lowercase(), text.lowercase(), doc.title.lowercase()))
        }

        for (block in model.blocks) {
            if (block is MarkdownBlock.Heading) {
                flush()
                heading = block.text
                anchor = block.anchor
                level = block.level
                isRealHeading = true
            } else {
                appendBlockText(block, body)
            }
        }
        flush()

        if (out.isEmpty()) {
            // A doc with no headings and no captured text: still index its title so it is findable.
            val section = DocSection(doc.id, doc.title, doc.group, 0, doc.title, "", 1, "")
            out.add(IndexedSection(section, doc.title.lowercase(), "", doc.title.lowercase()))
        }
        return out
    }

    private fun appendBlockText(block: MarkdownBlock, sb: StringBuilder) {
        when (block) {
            is MarkdownBlock.Paragraph -> sb.append(block.inlines.plainText()).append('\n')
            is MarkdownBlock.BulletList -> block.items.forEach { sb.append(it.inlines.plainText()).append('\n') }
            is MarkdownBlock.OrderedList -> block.items.forEach { sb.append(it.inlines.plainText()).append('\n') }
            is MarkdownBlock.CodeBlock -> sb.append(block.code).append('\n')
            is MarkdownBlock.Quote -> sb.append(block.inlines.plainText()).append('\n')
            is MarkdownBlock.Table -> {
                block.headers.forEach { sb.append(it.plainText()).append(' ') }
                sb.append('\n')
                block.rows.forEach { row -> row.forEach { sb.append(it.plainText()).append(' ') }; sb.append('\n') }
            }
            is MarkdownBlock.Admonition -> {
                block.title?.let { sb.append(it).append('\n') }
                block.children.forEach { appendBlockText(it, sb) }
            }
            is MarkdownBlock.Heading -> sb.append(block.text).append('\n') // nested headings (e.g. in admonitions)
            MarkdownBlock.HorizontalRule -> {}
        }
    }

    companion object {
        private const val HEADING_WEIGHT = 12
        private const val TITLE_WEIGHT = 6
        private const val BODY_WEIGHT = 2
        private const val EXACT_HEADING_BONUS = 20
        private const val MAX_LEVEL = 6
        private const val SNIPPET_LEN = 140

        private val TOKEN = Regex("[a-z0-9]+")

        /** Lowercases and splits [text] into alphanumeric search terms. */
        fun tokenize(text: String): List<String> =
            TOKEN.findAll(text.lowercase()).map { it.value }.filter { it.isNotBlank() }.toList()

        /** Counts non-overlapping occurrences of [needle] in [haystack] (both already lowercased). */
        fun countOccurrences(haystack: String, needle: String): Int {
            if (needle.isEmpty()) return 0
            var count = 0
            var from = 0
            while (true) {
                val idx = haystack.indexOf(needle, from)
                if (idx < 0) break
                count++
                from = idx + needle.length
            }
            return count
        }
    }
}
