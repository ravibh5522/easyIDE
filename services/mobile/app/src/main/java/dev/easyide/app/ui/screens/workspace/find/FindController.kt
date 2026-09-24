package dev.easyide.app.ui.screens.workspace.find

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import dev.easyide.app.ui.screens.workspace.EditHistories
import dev.easyide.app.ui.screens.workspace.EditorSelections
import dev.easyide.app.ui.screens.workspace.EditorSession
import dev.easyide.app.ui.screens.workspace.WorkspaceUiState
import dev.easyide.app.ui.screens.workspace.decor.DecorationLayer
import dev.easyide.app.ui.screens.workspace.decor.DecorationRegistry
import dev.easyide.app.ui.screens.workspace.decor.SearchMatchDecoration
import dev.easyide.app.ui.screens.workspace.edit.Match
import dev.easyide.app.ui.screens.workspace.edit.ReplaceResult
import dev.easyide.app.ui.screens.workspace.edit.SearchQuery
import dev.easyide.app.ui.screens.workspace.edit.SearchResult
import dev.easyide.app.ui.screens.workspace.edit.TextSearch
import dev.easyide.app.ui.screens.workspace.edit.TextState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest

/**
 * Find and replace in the active editable document, and the state the find bar shows.
 *
 * Incremental: typing in the bar, flipping an option or editing the document reruns the search
 * (debounced, off the main thread, cancelled by the next change), and the matches go to the
 * decoration layer [DecorationLayer.SearchMatches] - painted in the draw phase, so highlighting
 * costs no text relayout (decision 0018). The current match is selected in the editor through
 * [EditorSession.select], which reveals it only if it is off screen.
 *
 * Replace goes through [EditorSession.replaceText], so replace-one and replace-all are each a
 * single undo step. Engine-neutral: nothing here knows the text field.
 */
class FindController(
    private val scope: CoroutineScope,
    private val workspace: StateFlow<WorkspaceUiState>,
    private val selections: EditorSelections,
    private val decorations: DecorationRegistry,
    private val session: EditorSession,
) {
    var isOpen by mutableStateOf(false)
        private set
    var showReplace by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var replacement by mutableStateOf("")
        private set
    var caseSensitive by mutableStateOf(false)
        private set
    var wholeWord by mutableStateOf(false)
        private set
    var regex by mutableStateOf(false)
        private set

    /** The latest search; [SearchResult.Found] with no matches while idle. */
    var result by mutableStateOf<SearchResult>(IDLE)
        private set

    /** Index into the matches of the one the editor is on, or -1. */
    var current by mutableIntStateOf(-1)
        private set

    /** Occurrences the last replace-all changed, shown until the query changes; null otherwise. */
    var replacedCount by mutableStateOf<Int?>(null)
        private set

    /** Bumped each time the bar is asked to open, so it can take focus and select its text even if already open. */
    var openRequests by mutableIntStateOf(0)
        private set

    private var decoratedPath: String? = null
    private var navigateOnResult = false

    private data class Inputs(val open: Boolean, val query: SearchQuery)
    private data class Doc(val path: String, val text: String)

    init {
        scope.launch {
            combine(activeDocument(), snapshotFlow { Inputs(isOpen, SearchQuery(query, caseSensitive, wholeWord, regex)) }) { doc, inputs ->
                doc to inputs
            }.collectLatest { (doc, inputs) -> search(doc, inputs) }
        }
    }

    /** Opens the bar (with the replace field when [replace]), seeded from a one-line selection. */
    fun open(replace: Boolean) {
        val doc = workspace.value.activeTab
        val selected = doc?.let { selections[it.relativePath] }
        if (doc != null && selected != null && !selected.collapsed) {
            val text = doc.content.substring(selected.min.coerceAtMost(doc.content.length), selected.max.coerceAtMost(doc.content.length))
            if (text.length <= MAX_SEED_CHARS && '\n' !in text) updateQuery { query = text }
        }
        if (replace) showReplace = true
        isOpen = true
        openRequests++
        navigateOnResult = true
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        session.focusEditor()
    }

    fun toggleReplace() { showReplace = !showReplace }

    fun onQueryChange(text: String) = updateQuery { query = text }

    fun onReplacementChange(text: String) { replacement = text }

    fun toggleCase() = updateQuery { caseSensitive = !caseSensitive }

    fun toggleWholeWord() = updateQuery { wholeWord = !wholeWord }

    fun toggleRegex() = updateQuery { regex = !regex }

    fun next() = step(forward = true)

    fun previous() = step(forward = false)

    /** Replaces the current match (moving to it first if the caret is not on one), then goes to the next. */
    fun replaceCurrent() {
        val doc = currentDocument() ?: return
        val matches = matches()
        val match = matches.getOrNull(current) ?: return
        val text = TextSearch.replacementFor(doc.text, searchQuery(), match, replacement) ?: return
        navigateOnResult = true
        session.replaceText(doc.path, doc.text.replaceRange(match.start, match.end, text), TextRange(match.start + text.length))
    }

    fun replaceAll() {
        val doc = currentDocument() ?: return
        val q = searchQuery()
        val with = replacement
        scope.launch {
            val outcome = withContext(Dispatchers.Default) { TextSearch.replaceAll(doc.text, q, with) }
            // The document moved on while replacing: applying would overwrite the newer edit.
            if (workspace.value.activeTab?.content != doc.text) return@launch
            if (outcome !is ReplaceResult.Replaced || outcome.count == 0) return@launch
            val range = selections[doc.path]
            val before = TextState(doc.text, range.start.coerceIn(0, doc.text.length), range.end.coerceIn(0, doc.text.length))
            val after = EditHistories.carried(before, outcome.text)
            session.replaceText(doc.path, outcome.text, TextRange(after.selectionStart, after.selectionEnd))
            replacedCount = outcome.count
        }
    }

    private fun matches(): List<Match> = (result as? SearchResult.Found)?.matches.orEmpty()

    private fun searchQuery() = SearchQuery(query, caseSensitive, wholeWord, regex)

    private fun currentDocument(): Doc? = workspace.value.activeTab?.takeIf { it.editable }?.let { Doc(it.relativePath, it.content) }

    private fun activeDocument() = workspace.map { state -> state.activeTab?.takeIf { it.editable }?.let { Doc(it.relativePath, it.content) } }
        .distinctUntilChanged()

    /** A change of what is searched for: the next result selects its nearest match and the replace count is stale. */
    private inline fun updateQuery(change: () -> Unit) {
        change()
        replacedCount = null
        navigateOnResult = true
    }

    private fun step(forward: Boolean) {
        val doc = currentDocument() ?: return
        val matches = matches()
        if (matches.isEmpty()) return
        val range = selections[doc.path]
        // With no current match yet, start from the caret rather than from the first match.
        val next = if (current < 0) {
            if (forward) TextSearch.firstFrom(matches, range.max) else TextSearch.lastBefore(matches, range.min)
        } else TextSearch.step(matches.size, current, forward)
        current = next
        paint(doc, matches, next)
        session.select(doc.path, TextRange(matches[next].start, matches[next].end))
    }

    private suspend fun search(doc: Doc?, inputs: Inputs) {
        if (doc?.path != decoratedPath) clearDecorations()
        if (!inputs.open || doc == null || inputs.query.text.isEmpty()) {
            clearDecorations()
            result = IDLE
            current = -1
            return
        }
        delay(DEBOUNCE_MS)
        val outcome = withContext(Dispatchers.Default) { TextSearch.find(doc.text, inputs.query) }
        // A newer edit is already queued (this run is about to be cancelled); painting now would place matches on old text.
        if (workspace.value.activeTab?.content != doc.text) return
        result = outcome
        val matches = (outcome as? SearchResult.Found)?.matches.orEmpty()
        val range = selections[doc.path]
        val onMatch = matches.indexOfFirst { it.start == range.min && it.end == range.max }
        val index = when {
            matches.isEmpty() -> -1
            navigateOnResult -> TextSearch.firstFrom(matches, range.min)
            onMatch >= 0 -> onMatch
            else -> TextSearch.firstFrom(matches, range.max)
        }
        current = index
        paint(doc, matches, index)
        if (navigateOnResult && index >= 0) session.select(doc.path, TextRange(matches[index].start, matches[index].end))
        navigateOnResult = false
    }

    private fun paint(doc: Doc, matches: List<Match>, currentIndex: Int) {
        val items = matches.mapIndexed { i, m -> SearchMatchDecoration(m.start, m.end, isCurrent = i == currentIndex) }
        decorations.model(doc.path).set(DecorationLayer.SearchMatches, SOURCE, items, doc.text)
        decoratedPath = doc.path
    }

    private fun clearDecorations() {
        decoratedPath?.let { decorations.model(it).clear(DecorationLayer.SearchMatches, SOURCE) }
        decoratedPath = null
    }

    private companion object {
        const val SOURCE = "find"
        const val DEBOUNCE_MS = 100L

        /** A selection longer than this is not a search term; it seeds nothing. */
        const val MAX_SEED_CHARS = 200
        val IDLE = SearchResult.Found(emptyList(), truncated = false)
    }
}
