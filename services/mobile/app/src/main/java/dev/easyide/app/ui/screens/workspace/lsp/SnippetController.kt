package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.app.ui.screens.workspace.decor.OffsetEdit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The snippet being filled in, and the document it lives in. */
data class ActiveSnippet(val path: String, val text: String, val session: SnippetSession)

/**
 * Tab-stop navigation after a snippet completion (lsp-features.md 4.2, "basic" tab stops):
 * Tab / Shift+Tab or the touch "next field" chip move between fields, typing inside a field
 * keeps the fields in place, an edit outside every field or Escape ends the session.
 */
class SnippetController(private val ws: LspWorkspace) {

    private val state = MutableStateFlow<ActiveSnippet?>(null)

    val active: StateFlow<ActiveSnippet?> = state.asStateFlow()

    fun start(path: String, text: String, session: SnippetSession?) {
        state.value = session?.let { ActiveSnippet(path, text, it) }
    }

    fun end() {
        state.value = null
    }

    /** Follows a buffer change: shifts the fields, or ends the session. */
    fun onText(path: String, text: String) {
        val cur = state.value ?: return
        if (cur.path != path) {
            end()
            return
        }
        if (cur.text === text || cur.text == text) return
        val edit = OffsetEdit.between(cur.text, text) ?: return
        val moved = cur.session.shifted(edit.start, edit.oldEnd, edit.newEnd)
        state.value = moved?.let { ActiveSnippet(path, text, it) }
    }

    /** Moves to the next ([forward]) or previous field; false when there is no session. */
    fun move(forward: Boolean): Boolean {
        val cur = state.value ?: return false
        val tab = ws.tab(cur.path) ?: return false
        val next = if (forward) cur.session.next() else cur.session.previous()
        val atEnd = forward && !cur.session.hasNext
        val range = (if (atEnd) cur.session.active else next.active) ?: return false
        if (atEnd) {
            end()
            ws.select(cur.path, tab.content, range.end, range.end, reveal = false)
            return true
        }
        state.value = cur.copy(session = next)
        ws.select(cur.path, tab.content, range.first, range.end, reveal = false)
        // The final `$0` is a caret position, not a field: reaching it ends the session.
        if (next.stops.getOrNull(next.current)?.index == 0) end()
        return true
    }
}
