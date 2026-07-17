@file:OptIn(ExperimentalMaterial3Api::class)

package com.echidna.app.ui.help

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Stable test tags for the Help surface (instrumentation + Robolectric UI tests). */
object HelpTestTags {
    const val SCREEN = "help.screen"
    const val SEARCH_FIELD = "help.search"
    const val DOC_INDEX = "help.index"
    const val RESULTS = "help.results"
    const val NO_RESULTS = "help.noResults"
    const val DOC_VIEW = "help.doc"
    const val DOC_TITLE = "help.docTitle"
    const val DOC_BACK = "help.back"
    fun docRow(id: String) = "help.doc.$id"
    fun resultRow(index: Int) = "help.result.$index"
}

/**
 * The in-app Help surface: a searchable index of the bundled repository docs plus a native Markdown
 * doc reader. A single top-level screen — in-Help navigation (index → doc → doc-to-doc links, search
 * results, and Back) is driven entirely by [HelpViewModel], so the app nav graph needs only one route.
 */
@Composable
fun HelpScreen(
    viewModel: HelpViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    // System back navigates within Help (doc → previous → index) before leaving the screen.
    BackHandler(enabled = !state.isHome) { viewModel.back() }

    val openDocId = state.openDocId
    if (openDocId == null) {
        HelpIndex(
            state = state,
            onQueryChange = viewModel::onQueryChange,
            onClearQuery = viewModel::clearQuery,
            onOpenDoc = { viewModel.openDoc(it) },
            onOpenResult = viewModel::openResult,
            modifier = modifier.fillMaxSize().testTag(HelpTestTags.SCREEN),
        )
    } else {
        val doc = viewModel.doc(openDocId)
        HelpDocView(
            docId = openDocId,
            docTitle = doc?.title ?: openDocId,
            markdown = doc?.markdown ?: "",
            highlight = state.highlight,
            scrollAnchor = state.scrollAnchor,
            onScrollHandled = viewModel::onScrollHandled,
            onBack = { viewModel.back() },
            onLinkClick = { destination ->
                when (val target = HelpLinks.resolve(openDocId, destination, viewModel.knownDocIds)) {
                    is LinkTarget.InternalDoc -> viewModel.openDoc(target.docId, target.anchor, null)
                    is LinkTarget.SameDocAnchor -> viewModel.scrollToAnchor(target.anchor)
                    is LinkTarget.External -> runCatching { uriHandler.openUri(target.url) }
                    is LinkTarget.Unresolved -> Unit // relative link to a non-bundled target: no-op
                }
            },
            // Note: no SCREEN tag here — HelpDocView tags its root DOC_VIEW; chaining a second
            // testTag would mask it (the outer testTag wins).
            modifier = modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun HelpIndex(
    state: HelpUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onOpenDoc: (String) -> Unit,
    onOpenResult: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag(HelpTestTags.SEARCH_FIELD),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = onClearQuery) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                }
            },
            placeholder = { Text("Search the docs") },
            label = { Text("Help & Docs") },
        )

        if (state.isSearching) {
            SearchResults(state.results, onOpenResult)
        } else {
            DocIndexList(state.groups, onOpenDoc)
        }
    }
}

@Composable
private fun SearchResults(results: List<SearchResult>, onOpenResult: (SearchResult) -> Unit) {
    if (results.isEmpty()) {
        Text(
            text = "No matches in the documentation.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp).testTag(HelpTestTags.NO_RESULTS),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(HelpTestTags.RESULTS),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexedResults(results, onOpenResult)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedResults(
    results: List<SearchResult>,
    onOpenResult: (SearchResult) -> Unit,
) {
    items(results.size) { i ->
        val r = results[i]
        Card(
            onClick = { onOpenResult(r) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(HelpTestTags.resultRow(i)),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = if (r.sectionHeading.isNotBlank()) "${r.docTitle} · ${r.sectionHeading}" else r.docTitle,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = r.group,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (r.snippet.isNotBlank()) {
                    Text(
                        text = r.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DocIndexList(groups: List<HelpGroup>, onOpenDoc: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(HelpTestTags.DOC_INDEX),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        groups.forEach { group ->
            item(key = "group:${group.title}") {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            items(group.docs, key = { it.id }) { doc ->
                Card(
                    onClick = { onOpenDoc(doc.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(HelpTestTags.docRow(doc.id)),
                ) {
                    Text(
                        text = doc.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpDocView(
    docId: String,
    docTitle: String,
    markdown: String,
    highlight: String?,
    scrollAnchor: String?,
    onScrollHandled: () -> Unit,
    onBack: () -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = remember(markdown) { MarkdownParser.parse(markdown) }
    val listState = rememberLazyListState()

    // Scroll to the requested section anchor once the doc/anchor is set.
    LaunchedEffect(scrollAnchor, model) {
        val anchor = scrollAnchor ?: return@LaunchedEffect
        val index = model.blockIndexForAnchor(anchor)
        if (index != null) listState.animateScrollToItem(index)
        onScrollHandled()
    }

    Column(modifier = modifier.testTag(HelpTestTags.DOC_VIEW)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag(HelpTestTags.DOC_BACK)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = docTitle,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.testTag(HelpTestTags.DOC_TITLE),
            )
        }
        Spacer(Modifier.height(4.dp))
        MarkdownDocument(
            model = model,
            onLinkClick = onLinkClick,
            highlight = highlight,
            listState = listState,
            docId = docId,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        )
    }
}
