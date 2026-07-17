package com.echidna.app.ui.help

/**
 * A small, self-contained Markdown parser covering the constructs used by the repository docs:
 * ATX (and setext-`=`) headings, paragraphs, fenced code blocks, bullet/ordered lists, blockquotes,
 * pipe tables, horizontal rules, MkDocs admonitions, and inline emphasis/code/links (inline and
 * reference style).
 *
 * It is deliberately *not* a full CommonMark implementation — the goal is faithful, offline
 * rendering of this project's own documentation with zero third-party dependencies (no network, no
 * CDN). Being pure Kotlin (no Android types) it is unit-tested directly on the JVM.
 */
object MarkdownParser {

    private val ATX_HEADING = Regex("^(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$")
    private val FENCE = Regex("^(```+|~~~+)\\s*([^`]*)$")
    private val HR = Regex("^\\s*(?:(?:-\\s*){3,}|(?:\\*\\s*){3,}|(?:_\\s*){3,})$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val REF_DEF = Regex("^\\s*\\[([^\\]]+)]:\\s*(\\S+)(?:\\s+.*)?$")
    private val ADMONITION = Regex("^(!!!|\\?\\?\\?\\+?)\\s+(\\w+)(?:\\s+\"([^\"]*)\")?\\s*$")

    /** Parses [source] into a [MarkdownDocumentModel]. */
    fun parse(source: String): MarkdownDocumentModel {
        val normalized = source.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split("\n")
        // First pass: collect (and drop) reference link definitions so `[text][id]` resolves.
        val refs = HashMap<String, String>()
        val contentLines = ArrayList<String>(lines.size)
        for (line in lines) {
            val m = REF_DEF.matchEntire(line)
            if (m != null) {
                refs[m.groupValues[1].trim().lowercase()] = m.groupValues[2].trim()
            } else {
                contentLines.add(line)
            }
        }
        val blocks = parseBlocks(contentLines, 0, refs)
        return MarkdownDocumentModel(blocks)
    }

    /**
     * Parses block-level structure from [lines], treating leading indentation of [baseIndent] spaces
     * as the block boundary (used for the indented body of admonitions).
     */
    private fun parseBlocks(lines: List<String>, baseIndent: Int, refs: Map<String, String>): List<MarkdownBlock> {
        val blocks = ArrayList<MarkdownBlock>()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = if (baseIndent > 0) raw.removePrefix(" ".repeat(baseIndent)) else raw

            if (line.isBlank()) {
                i++
                continue
            }

            // Fenced code block.
            val fence = FENCE.matchEntire(line.trimEnd())
            if (fence != null && line.trimStart().startsWith(fence.groupValues[1].first().toString())) {
                val fenceMarker = fence.groupValues[1]
                val lang = fence.groupValues[2].trim().ifEmpty { null }
                val code = StringBuilder()
                i++
                while (i < lines.size) {
                    val body = if (baseIndent > 0) lines[i].removePrefix(" ".repeat(baseIndent)) else lines[i]
                    if (body.trimStart().startsWith(fenceMarker.first().toString().repeat(3)) &&
                        body.trim().all { it == fenceMarker.first() }
                    ) {
                        i++
                        break
                    }
                    code.appendLine(body)
                    i++
                }
                blocks.add(MarkdownBlock.CodeBlock(code.toString().trimEnd('\n'), lang))
                continue
            }

            // MkDocs admonition: `!!! kind "Title"` then a 4-space-indented body.
            val adm = ADMONITION.matchEntire(line.trim())
            if (adm != null) {
                val kind = adm.groupValues[2]
                val title = adm.groupValues[3].ifEmpty { null }
                val bodyLines = ArrayList<String>()
                i++
                while (i < lines.size) {
                    val next = lines[i]
                    if (next.isBlank()) {
                        bodyLines.add("")
                        i++
                        continue
                    }
                    // Body lines are indented (relative to the admonition marker) by >= 4 spaces.
                    if (next.length - next.trimStart().length >= baseIndent + 4) {
                        bodyLines.add(next)
                        i++
                    } else {
                        break
                    }
                }
                // Trim trailing blanks captured inside the block.
                while (bodyLines.isNotEmpty() && bodyLines.last().isBlank()) bodyLines.removeAt(bodyLines.size - 1)
                val children = parseBlocks(bodyLines, baseIndent + 4, refs)
                blocks.add(MarkdownBlock.Admonition(kind, title, children))
                continue
            }

            // Horizontal rule. `---`/`***`/`___` on their own line become a rule; setext `===`
            // headings (handled further down) are unaffected since `=` is not an HR marker.
            if (HR.matchEntire(line) != null) {
                blocks.add(MarkdownBlock.HorizontalRule)
                i++
                continue
            }

            // ATX heading.
            val atx = ATX_HEADING.matchEntire(line)
            if (atx != null) {
                val level = atx.groupValues[1].length
                val inlines = parseInlines(atx.groupValues[2].trim(), refs)
                val text = inlines.plainText()
                blocks.add(MarkdownBlock.Heading(level, inlines, slugify(text), text))
                i++
                continue
            }

            // Table: a `|`-bearing line immediately followed by a delimiter row.
            if (line.contains('|') && i + 1 < lines.size) {
                val nextRaw = lines[i + 1]
                val next = if (baseIndent > 0) nextRaw.removePrefix(" ".repeat(baseIndent)) else nextRaw
                if (isTableDelimiter(next)) {
                    val headers = splitTableRow(line).map { parseInlines(it, refs) }
                    val rows = ArrayList<List<List<MarkdownInline>>>()
                    i += 2
                    while (i < lines.size) {
                        val rowRaw = if (baseIndent > 0) lines[i].removePrefix(" ".repeat(baseIndent)) else lines[i]
                        if (rowRaw.isBlank() || !rowRaw.contains('|')) break
                        rows.add(splitTableRow(rowRaw).map { parseInlines(it, refs) })
                        i++
                    }
                    blocks.add(MarkdownBlock.Table(headers, rows))
                    continue
                }
            }

            // Blockquote.
            if (line.trimStart().startsWith(">")) {
                val quote = StringBuilder()
                while (i < lines.size) {
                    val q = if (baseIndent > 0) lines[i].removePrefix(" ".repeat(baseIndent)) else lines[i]
                    if (!q.trimStart().startsWith(">")) break
                    quote.append(q.trimStart().removePrefix(">").removePrefix(" "))
                    quote.append(' ')
                    i++
                }
                blocks.add(MarkdownBlock.Quote(parseInlines(quote.toString().trim(), refs)))
                continue
            }

            // Bullet list.
            if (BULLET.matchEntire(line) != null && ORDERED.matchEntire(line) == null) {
                val items = ArrayList<MarkdownListItem>()
                while (i < lines.size) {
                    val l = if (baseIndent > 0) lines[i].removePrefix(" ".repeat(baseIndent)) else lines[i]
                    val bm = BULLET.matchEntire(l) ?: break
                    val itemText = StringBuilder(bm.groupValues[2])
                    i++
                    // Fold indented continuation lines into the same item.
                    while (i < lines.size) {
                        val cont = if (baseIndent > 0) lines[i].removePrefix(" ".repeat(baseIndent)) else lines[i]
                        if (cont.isBlank() || BULLET.matchEntire(cont) != null || ORDERED.matchEntire(cont) != null) break
                        if (cont.startsWith("  ")) {
                            itemText.append(' ').append(cont.trim())
                            i++
                        } else break
                    }
                    items.add(MarkdownListItem(parseInlines(itemText.toString().trim(), refs)))
                }
                blocks.add(MarkdownBlock.BulletList(items))
                continue
            }

            // Ordered list.
            val firstOrdered = ORDERED.matchEntire(line)
            if (firstOrdered != null) {
                val start = firstOrdered.groupValues[2].toIntOrNull() ?: 1
                val items = ArrayList<MarkdownListItem>()
                while (i < lines.size) {
                    val l = if (baseIndent > 0) lines[i].removePrefix(" ".repeat(baseIndent)) else lines[i]
                    val om = ORDERED.matchEntire(l) ?: break
                    val itemText = StringBuilder(om.groupValues[3])
                    i++
                    while (i < lines.size) {
                        val cont = if (baseIndent > 0) lines[i].removePrefix(" ".repeat(baseIndent)) else lines[i]
                        if (cont.isBlank() || BULLET.matchEntire(cont) != null || ORDERED.matchEntire(cont) != null) break
                        if (cont.startsWith("  ")) {
                            itemText.append(' ').append(cont.trim())
                            i++
                        } else break
                    }
                    items.add(MarkdownListItem(parseInlines(itemText.toString().trim(), refs)))
                }
                blocks.add(MarkdownBlock.OrderedList(start, items))
                continue
            }

            // Setext heading: a text line followed by `===`/`---`.
            if (i + 1 < lines.size) {
                val underline = (if (baseIndent > 0) lines[i + 1].removePrefix(" ".repeat(baseIndent)) else lines[i + 1]).trim()
                if (underline.isNotEmpty() && (underline.all { it == '=' } || underline.all { it == '-' }) && underline.length >= 2) {
                    val level = if (underline[0] == '=') 1 else 2
                    val inlines = parseInlines(line.trim(), refs)
                    val text = inlines.plainText()
                    blocks.add(MarkdownBlock.Heading(level, inlines, slugify(text), text))
                    i += 2
                    continue
                }
            }

            // Paragraph: accumulate consecutive "plain" lines.
            val para = StringBuilder(line.trim())
            i++
            while (i < lines.size) {
                val p = if (baseIndent > 0) lines[i].removePrefix(" ".repeat(baseIndent)) else lines[i]
                if (p.isBlank() || isBlockStart(p, lines, i, baseIndent)) break
                para.append(' ').append(p.trim())
                i++
            }
            val inlines = parseInlines(para.toString(), refs)
            // A paragraph that is nothing but a single image becomes a block-level image (rendered as
            // a real picture with a caption); images mixed with prose stay inline (degrade to alt).
            val loneImage = inlines.loneImageOrNull()
            if (loneImage != null) {
                blocks.add(MarkdownBlock.Image(loneImage.alt, loneImage.destination))
            } else {
                blocks.add(MarkdownBlock.Paragraph(inlines))
            }
        }
        return blocks
    }

    /** The sole [MarkdownInline.Image] in these inlines (ignoring blank text), or null if not lone. */
    private fun List<MarkdownInline>.loneImageOrNull(): MarkdownInline.Image? {
        var image: MarkdownInline.Image? = null
        for (node in this) {
            when (node) {
                is MarkdownInline.Image -> if (image == null) image = node else return null
                is MarkdownInline.Text -> if (node.text.isNotBlank()) return null
                else -> return null
            }
        }
        return image
    }

    /** True when [line] begins a block that must interrupt an in-progress paragraph. */
    private fun isBlockStart(line: String, lines: List<String>, index: Int, baseIndent: Int): Boolean {
        if (ATX_HEADING.matchEntire(line) != null) return true
        if (FENCE.matchEntire(line.trimEnd()) != null) return true
        if (HR.matchEntire(line) != null) return true
        if (BULLET.matchEntire(line) != null || ORDERED.matchEntire(line) != null) return true
        if (line.trimStart().startsWith(">")) return true
        if (ADMONITION.matchEntire(line.trim()) != null) return true
        if (line.contains('|') && index + 1 < lines.size) {
            val next = if (baseIndent > 0) lines[index + 1].removePrefix(" ".repeat(baseIndent)) else lines[index + 1]
            if (isTableDelimiter(next)) return true
        }
        return false
    }

    private fun isTableDelimiter(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('-') || !trimmed.contains('|') && !trimmed.startsWith(':') && !trimmed.startsWith('-')) return false
        val cells = trimmed.trim('|').split('|')
        if (cells.isEmpty()) return false
        return cells.all { cell ->
            val c = cell.trim()
            c.isNotEmpty() && c.all { it == '-' || it == ':' } && c.contains('-')
        }
    }

    private fun splitTableRow(line: String): List<String> =
        line.trim().trim('|').split('|').map { it.trim() }

    // ---- Inline parsing -------------------------------------------------------

    /** Parses inline markup (emphasis, code spans, links) from a single logical line of text. */
    fun parseInlines(text: String, refs: Map<String, String> = emptyMap()): List<MarkdownInline> {
        val out = ArrayList<MarkdownInline>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(MarkdownInline.Text(buf.toString()))
                buf.setLength(0)
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> { // escape
                    buf.append(text[i + 1]); i += 2
                }
                c == '`' -> {
                    val run = countRun(text, i, '`')
                    val close = findRun(text, i + run, '`', run)
                    if (close >= 0) {
                        flush()
                        out.add(MarkdownInline.Code(text.substring(i + run, close).trim()))
                        i = close + run
                    } else { buf.append(c); i++ }
                }
                (c == '*' || c == '_') && startsDelim(text, i, "$c$c") -> {
                    val close = findDelim(text, i + 2, "$c$c")
                    if (close >= 0) {
                        flush()
                        out.add(MarkdownInline.Bold(parseInlines(text.substring(i + 2, close), refs)))
                        i = close + 2
                    } else { buf.append(c); i++ }
                }
                (c == '*' || c == '_') -> {
                    val close = findDelim(text, i + 1, "$c")
                    if (close >= 0 && close > i + 1) {
                        flush()
                        out.add(MarkdownInline.Italic(parseInlines(text.substring(i + 1, close), refs)))
                        i = close + 1
                    } else { buf.append(c); i++ }
                }
                c == '!' && i + 1 < text.length && text[i + 1] == '[' -> {
                    // Image: emit an Image inline carrying alt + destination (the renderer decodes the
                    // bundled asset, or degrades to a caption). A lone image line is later promoted to
                    // a block-level image; here it stays inline for the mixed-with-prose case.
                    val parsed = parseImage(text, i + 1, refs)
                    if (parsed != null) {
                        flush()
                        out.add(parsed.first)
                        i = parsed.second
                    } else { buf.append(c); i++ }
                }
                c == '[' -> {
                    val link = parseLink(text, i, refs)
                    if (link != null) {
                        flush()
                        out.add(link.first)
                        i = link.second
                    } else { buf.append(c); i++ }
                }
                c == '<' -> {
                    // Autolink <http://...>.
                    val end = text.indexOf('>', i + 1)
                    val inner = if (end > 0) text.substring(i + 1, end) else ""
                    if (end > 0 && (inner.startsWith("http://") || inner.startsWith("https://") || inner.startsWith("mailto:"))) {
                        flush()
                        out.add(MarkdownInline.Link(listOf(MarkdownInline.Text(inner)), inner))
                        i = end + 1
                    } else { buf.append(c); i++ }
                }
                else -> { buf.append(c); i++ }
            }
        }
        flush()
        return out
    }

    /** Parses `[text](url)` or `[text][ref]`/`[ref]` starting at the `[` at [start]. */
    private fun parseLink(text: String, start: Int, refs: Map<String, String>): Pair<MarkdownInline.Link, Int>? {
        val close = matchingBracket(text, start) ?: return null
        val label = text.substring(start + 1, close)
        val children = parseInlines(label, refs)
        // Inline: [label](dest)
        if (close + 1 < text.length && text[close + 1] == '(') {
            val paren = matchingParen(text, close + 1) ?: return null
            var dest = text.substring(close + 2, paren).trim()
            // Strip an optional "title" and surrounding <>.
            dest = dest.substringBefore(' ').removePrefix("<").removeSuffix(">")
            return MarkdownInline.Link(children, dest) to (paren + 1)
        }
        // Reference: [label][ref] or [label][] or shortcut [ref]
        if (close + 1 < text.length && text[close + 1] == '[') {
            val refClose = text.indexOf(']', close + 2)
            if (refClose >= 0) {
                val refId = text.substring(close + 2, refClose).trim().ifEmpty { label }.lowercase()
                val dest = refs[refId] ?: return null
                return MarkdownInline.Link(children, dest) to (refClose + 1)
            }
        }
        val shortcut = refs[label.trim().lowercase()]
        if (shortcut != null) {
            return MarkdownInline.Link(children, shortcut) to (close + 1)
        }
        return null
    }

    /**
     * Parses an image `![alt](url)` (or reference form `![alt][ref]`) whose `[` is at [bracketStart].
     * Returns the [MarkdownInline.Image] (alt text kept literal, destination preserved) and end index,
     * or null when there is no destination to attach.
     */
    private fun parseImage(text: String, bracketStart: Int, refs: Map<String, String>): Pair<MarkdownInline.Image, Int>? {
        val close = matchingBracket(text, bracketStart) ?: return null
        val alt = text.substring(bracketStart + 1, close)
        // Inline: ![alt](dest "optional title")
        if (close + 1 < text.length && text[close + 1] == '(') {
            val paren = matchingParen(text, close + 1) ?: return null
            var dest = text.substring(close + 2, paren).trim()
            dest = dest.substringBefore(' ').removePrefix("<").removeSuffix(">")
            return MarkdownInline.Image(alt, dest) to (paren + 1)
        }
        // Reference: ![alt][ref] or shortcut ![ref].
        if (close + 1 < text.length && text[close + 1] == '[') {
            val refClose = text.indexOf(']', close + 2)
            if (refClose >= 0) {
                val refId = text.substring(close + 2, refClose).trim().ifEmpty { alt }.lowercase()
                val dest = refs[refId] ?: return null
                return MarkdownInline.Image(alt, dest) to (refClose + 1)
            }
        }
        val shortcut = refs[alt.trim().lowercase()]
        if (shortcut != null) return MarkdownInline.Image(alt, shortcut) to (close + 1)
        return null
    }

    private fun matchingBracket(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    private fun matchingParen(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    private fun countRun(text: String, start: Int, ch: Char): Int {
        var n = 0
        while (start + n < text.length && text[start + n] == ch) n++
        return n
    }

    private fun findRun(text: String, from: Int, ch: Char, len: Int): Int {
        var i = from
        while (i <= text.length - len) {
            if (text.substring(i, i + len).all { it == ch } &&
                (i + len == text.length || text[i + len] != ch) &&
                (i == 0 || text[i - 1] != ch)
            ) return i
            i++
        }
        return -1
    }

    private fun startsDelim(text: String, i: Int, delim: String): Boolean =
        i + delim.length <= text.length && text.substring(i, i + delim.length) == delim

    private fun findDelim(text: String, from: Int, delim: String): Int {
        var i = from
        while (i <= text.length - delim.length) {
            if (text[i] != '\\' && text.substring(i, i + delim.length) == delim) return i
            i++
        }
        return -1
    }
}
