package com.echidna.app.ui.help

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/** Annotation tag carrying a link's raw destination in the built [AnnotatedString]. */
private const val LINK_TAG = "md-link"

/**
 * Renders a list of parsed [MarkdownBlock]s into Compose, theme-aware (colors/typography come from
 * the active [MaterialTheme], so it honors the app's light/dark/accent theming). [highlight] terms
 * are emphasised with a background tint (used for search hits); link taps are reported via
 * [onLinkClick] with the raw destination for the caller to route.
 */
@Composable
fun MarkdownBlocks(
    blocks: List<MarkdownBlock>,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    highlight: String? = null,
    docId: String? = null,
) {
    val highlightTerms = remember(highlight) { highlight?.let { DocSearchIndex.tokenize(it) }.orEmpty() }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block -> MarkdownBlockView(block, highlightTerms, onLinkClick, docId) }
    }
}

/**
 * Renders a full [MarkdownDocumentModel] as a scrollable [LazyColumn], so the caller can scroll to a
 * specific heading (via [MarkdownDocumentModel.blockIndexForAnchor] + [listState]). Otherwise
 * identical to [MarkdownBlocks].
 */
@Composable
fun MarkdownDocument(
    model: MarkdownDocumentModel,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    highlight: String? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    docId: String? = null,
) {
    val highlightTerms = remember(highlight) { highlight?.let { DocSearchIndex.tokenize(it) }.orEmpty() }
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(model.blocks) { _, block -> MarkdownBlockView(block, highlightTerms, onLinkClick, docId) }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    highlightTerms: List<String>,
    onLinkClick: (String) -> Unit,
    docId: String?,
) {
    when (block) {
        is MarkdownBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            InlineText(
                block.inlines,
                highlightTerms,
                onLinkClick,
                style.copy(fontWeight = FontWeight.SemiBold),
                Modifier.padding(top = 8.dp),
            )
        }

        is MarkdownBlock.Paragraph ->
            InlineText(block.inlines, highlightTerms, onLinkClick, MaterialTheme.typography.bodyMedium)

        is MarkdownBlock.BulletList ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.items.forEach { item ->
                    Row {
                        Text("•  ", style = MaterialTheme.typography.bodyMedium)
                        InlineText(item.inlines, highlightTerms, onLinkClick, MaterialTheme.typography.bodyMedium)
                    }
                }
            }

        is MarkdownBlock.OrderedList ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.items.forEachIndexed { i, item ->
                    Row {
                        Text("${block.start + i}.  ", style = MaterialTheme.typography.bodyMedium)
                        InlineText(item.inlines, highlightTerms, onLinkClick, MaterialTheme.typography.bodyMedium)
                    }
                }
            }

        is MarkdownBlock.CodeBlock ->
            // Mermaid renders natively on the web docs; there is no dependency-free in-app renderer,
            // so degrade the fence to a labeled caption instead of dumping raw diagram source.
            if (block.language.equals("mermaid", ignoreCase = true)) {
                CaptionCard(
                    label = "Diagram — view in the web docs",
                    body = "This diagram renders in the web documentation.",
                )
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Text(
                        text = block.code,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

        is MarkdownBlock.Image -> ImageBlockView(block.alt, block.destination, docId)

        is MarkdownBlock.Quote ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp),
            ) {
                InlineText(block.inlines, highlightTerms, onLinkClick, MaterialTheme.typography.bodyMedium)
            }

        is MarkdownBlock.Admonition -> AdmonitionView(block, highlightTerms, onLinkClick, docId)

        is MarkdownBlock.Table -> TableView(block, highlightTerms, onLinkClick)

        MarkdownBlock.HorizontalRule ->
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
    }
}

@Composable
private fun AdmonitionView(
    block: MarkdownBlock.Admonition,
    highlightTerms: List<String>,
    onLinkClick: (String) -> Unit,
    docId: String?,
) {
    val accent = when (block.kind.lowercase()) {
        "danger", "error", "bug", "caution" -> MaterialTheme.colorScheme.error
        "warning", "attention" -> MaterialTheme.colorScheme.tertiary
        "success", "tip", "check" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Spacer(Modifier.width(4.dp).height(1.dp).background(accent))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = block.title ?: block.kind.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelLarge,
                color = accent,
            )
            block.children.forEach { MarkdownBlockView(it, highlightTerms, onLinkClick, docId) }
        }
    }
}

@Composable
private fun TableView(
    block: MarkdownBlock.Table,
    highlightTerms: List<String>,
    onLinkClick: (String) -> Unit,
) {
    val columns = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0)
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
    ) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
            for (c in 0 until columns) {
                TableCell {
                    InlineText(
                        block.headers.getOrElse(c) { emptyList() },
                        highlightTerms,
                        onLinkClick,
                        MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
        block.rows.forEach { row ->
            Row {
                for (c in 0 until columns) {
                    TableCell {
                        InlineText(
                            row.getOrElse(c) { emptyList() },
                            highlightTerms,
                            onLinkClick,
                            MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(content: @Composable () -> Unit) {
    Column(Modifier.width(160.dp).padding(8.dp)) { content() }
}

/**
 * Renders inline markup as clickable text: emphasis/code styling, search-term highlight backgrounds,
 * and link taps routed to [onLinkClick].
 */
@Composable
private fun InlineText(
    inlines: List<MarkdownInline>,
    highlightTerms: List<String>,
    onLinkClick: (String) -> Unit,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val baseColor = LocalContentColor.current
    val annotated = buildAnnotatedString {
        appendInlines(
            inlines,
            linkColor = colors.primary,
            codeBackground = colors.surfaceVariant,
            highlightBackground = colors.tertiaryContainer,
            highlightTerms = highlightTerms,
        )
    }
    ClickableText(
        text = annotated,
        style = style.copy(color = baseColor),
        modifier = modifier,
        onClick = { offset ->
            annotated.getStringAnnotations(LINK_TAG, offset, offset).firstOrNull()?.let {
                onLinkClick(it.item)
            }
        },
    )
}

/** Recursively appends [inlines] to the builder, applying emphasis, code, highlight, and link spans. */
private fun AnnotatedString.Builder.appendInlines(
    inlines: List<MarkdownInline>,
    linkColor: Color,
    codeBackground: Color,
    highlightBackground: Color,
    highlightTerms: List<String>,
) {
    inlines.forEach { inline ->
        when (inline) {
            is MarkdownInline.Text -> appendHighlighted(inline.text, highlightTerms, highlightBackground)
            is MarkdownInline.Bold -> withStyleSafe(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlines(inline.children, linkColor, codeBackground, highlightBackground, highlightTerms)
            }
            is MarkdownInline.Italic -> withStyleSafe(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlines(inline.children, linkColor, codeBackground, highlightBackground, highlightTerms)
            }
            is MarkdownInline.Code -> withStyleSafe(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground),
            ) {
                appendHighlighted(inline.text, highlightTerms, highlightBackground)
            }
            is MarkdownInline.Link -> {
                pushStringAnnotation(LINK_TAG, inline.destination)
                withStyleSafe(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    appendInlines(inline.children, linkColor, codeBackground, highlightBackground, highlightTerms)
                }
                pop()
            }
            // An image mixed inline with prose degrades to its alt text (a lone image line is instead
            // promoted to a block-level image and rendered as a real picture — see ImageBlockView).
            is MarkdownInline.Image -> appendHighlighted(inline.alt, highlightTerms, highlightBackground)
        }
    }
}

/**
 * Renders a block-level image. PNG/WebP are decoded from the bundled Help assets via [AssetManager]
 * and drawn full-width with an italic caption. SVG, remote `http(s)` URLs, and missing/undecodable
 * assets degrade to a labeled caption card (never raw `![...]` markup), keeping the Help APK
 * dependency-free while staying honest about what it cannot show inline.
 */
@Composable
private fun ImageBlockView(alt: String, dest: String, docId: String?) {
    val context = LocalContext.current
    val bare = dest.trim().substringBefore('#').substringBefore('?').lowercase()
    when {
        bare.startsWith("http://") || bare.startsWith("https://") ->
            CaptionCard(label = "Image — view in the web docs", body = alt)

        bare.endsWith(".svg") ->
            CaptionCard(label = "Vector image — view in the web docs", body = alt)

        else -> {
            val bitmap = remember(dest, docId) { decodeHelpAsset(context, docId, dest) }
            if (bitmap != null) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = alt.ifBlank { null },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.FillWidth,
                    )
                    if (alt.isNotBlank()) {
                        Text(
                            text = alt,
                            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                CaptionCard(label = "Image unavailable", body = alt)
            }
        }
    }
}

/** A labeled surface-tinted card used for images/diagrams that cannot be drawn inline. */
@Composable
private fun CaptionCard(label: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (body.isNotBlank()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Decodes a doc-relative image [dest] from the bundled Help assets, or null if absent/undecodable. */
private fun decodeHelpAsset(context: Context, docId: String?, dest: String): ImageBitmap? {
    val assetPath = helpAssetPath(docId, dest) ?: return null
    return runCatching {
        context.assets.open(assetPath).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
    }.getOrNull()
}

/**
 * Resolves a doc-relative image [dest] (as written in the Markdown) to its staged Help asset path,
 * e.g. `assets/screenshots/x.png` in `screenshots.md` → `help/docs/assets/screenshots/x.png`, and
 * `../assets/x.png` in `hardening/y.md` → `help/docs/assets/x.png`. Returns null if [dest] has no
 * path (pure anchor) or escapes the docs root. Pure (no Android deps) so it is unit-tested directly.
 */
internal fun helpAssetPath(docId: String?, dest: String): String? {
    val path = dest.trim().substringBefore('#').substringBefore('?')
    if (path.isEmpty()) return null
    val normalized = if (docId != null) HelpLinks.normalize(docId, path) else path.trimStart('/').ifEmpty { null }
    if (normalized.isNullOrEmpty()) return null
    return "${HelpRepository.ASSET_ROOT}/$normalized"
}

/** Appends [text], wrapping any [highlightTerms] occurrences (case-insensitive) in a tint background. */
private fun AnnotatedString.Builder.appendHighlighted(
    text: String,
    highlightTerms: List<String>,
    highlightBackground: Color,
) {
    if (highlightTerms.isEmpty()) {
        append(text)
        return
    }
    val lower = text.lowercase()
    var i = 0
    while (i < text.length) {
        // Find the earliest highlight-term match at or after i.
        var matchStart = -1
        var matchLen = 0
        for (term in highlightTerms) {
            if (term.isEmpty()) continue
            val at = lower.indexOf(term, i)
            if (at >= 0 && (matchStart == -1 || at < matchStart)) {
                matchStart = at
                matchLen = term.length
            }
        }
        if (matchStart < 0) {
            append(text.substring(i))
            break
        }
        if (matchStart > i) append(text.substring(i, matchStart))
        withStyleSafe(SpanStyle(background = highlightBackground)) {
            append(text.substring(matchStart, matchStart + matchLen))
        }
        i = matchStart + matchLen
    }
}

private inline fun AnnotatedString.Builder.withStyleSafe(style: SpanStyle, block: AnnotatedString.Builder.() -> Unit) {
    val idx = pushStyle(style)
    try {
        block()
    } finally {
        pop(idx)
    }
}
