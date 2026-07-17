package com.echidna.app.ui.help

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Immutable UI state for the Help surface. When [openDocId] is null the index/home is shown
 * (either the grouped doc list, or ranked search results when [query] is non-blank); otherwise the
 * named doc is shown, scrolled to [scrollAnchor] with [highlight] terms emphasised.
 */
data class HelpUiState(
    val loading: Boolean = true,
    val groups: List<HelpGroup> = emptyList(),
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val openDocId: String? = null,
    val scrollAnchor: String? = null,
    val highlight: String? = null,
) {
    val isHome: Boolean get() = openDocId == null
    val isSearching: Boolean get() = query.isNotBlank()
}

/**
 * Backs the Help screen: loads the bundled docs, drives full-text search, and manages in-Help
 * navigation (doc index → doc → doc-to-doc links) with its own back stack, so Help needs only a
 * single top-level nav route.
 *
 * [docsLoader] is injectable so unit/Robolectric tests can supply synthetic docs without assets.
 */
class HelpViewModel(
    application: Application,
    docsLoader: (Application) -> List<HelpDoc>,
) : AndroidViewModel(application) {

    /**
     * Production constructor used by the default `AndroidViewModelFactory` (it reflects for a
     * single-[Application] constructor — a default-valued lambda param does not satisfy that).
     */
    constructor(application: Application) : this(application, { HelpRepository.load(it) })

    private val docs: List<HelpDoc> = docsLoader(application)
    private val docsById: Map<String, HelpDoc> = docs.associateBy { it.id }
    private val index = DocSearchIndex(docs)

    /** Ids of every bundled doc — used by the renderer to classify internal vs external links. */
    val knownDocIds: Set<String> = docsById.keys

    private val backStack = ArrayDeque<String>()

    private val _state = MutableStateFlow(
        HelpUiState(loading = false, groups = HelpRepository.group(docs)),
    )
    val state: StateFlow<HelpUiState> = _state.asStateFlow()

    fun doc(id: String): HelpDoc? = docsById[id]

    /** Updates the search query and recomputes ranked results (blank query clears results). */
    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(
            query = query,
            results = if (query.isBlank()) emptyList() else index.search(query),
        )
    }

    fun clearQuery() = onQueryChange("")

    /** Opens a search result at its section, highlighting the current query. */
    fun openResult(result: SearchResult) {
        openDoc(result.docId, result.sectionAnchor.ifEmpty { null }, _state.value.query.trim().ifEmpty { null })
    }

    /**
     * Opens [docId], pushing the currently open doc onto the back stack. [anchor] scrolls to a
     * section; [highlight] emphasises matching terms in the rendered doc.
     */
    fun openDoc(docId: String, anchor: String? = null, highlight: String? = null) {
        if (docId !in docsById) return
        _state.value.openDocId?.let { backStack.addLast(it) }
        _state.value = _state.value.copy(
            openDocId = docId,
            scrollAnchor = anchor,
            highlight = highlight,
        )
    }

    /** Scrolls to [anchor] within the currently open doc (same-doc link). */
    fun scrollToAnchor(anchor: String) {
        _state.value = _state.value.copy(scrollAnchor = anchor)
    }

    /** Consumes the pending scroll target after the UI has scrolled to it. */
    fun onScrollHandled() {
        if (_state.value.scrollAnchor != null) {
            _state.value = _state.value.copy(scrollAnchor = null)
        }
    }

    /**
     * Navigates back one step: to the previous doc if one is on the stack, otherwise to the index.
     * Returns true if it handled the back (there was somewhere to go within Help).
     */
    fun back(): Boolean {
        if (_state.value.openDocId == null) return false
        val previous = backStack.removeLastOrNull()
        _state.value = _state.value.copy(
            openDocId = previous,
            scrollAnchor = null,
            highlight = if (previous == null) null else _state.value.highlight,
        )
        return true
    }

    /** Returns to the doc index, clearing the in-Help back stack. */
    fun goHome() {
        backStack.clear()
        _state.value = _state.value.copy(openDocId = null, scrollAnchor = null, highlight = null)
    }
}
