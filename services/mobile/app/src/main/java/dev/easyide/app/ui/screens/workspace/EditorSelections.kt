package dev.easyide.app.ui.screens.workspace

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The caret/selection of every open tab, hoisted out of the editor composable so that
 * code outside it can read and move it: extension actions read `${lineNumber}` and
 * `${selectedText}`, snippets insert at the caret, `openFile` lands on a line, and the
 * `editorHasSelection` context key follows it.
 *
 * Snapshot state, so the editor recomposes when a non-UI caller moves the caret; [changes]
 * bumps on every write for observers outside composition (context keys). Writes happen
 * on the main thread (composition and main-dispatched actions).
 */
class EditorSelections {
    private val ranges = mutableStateMapOf<String, TextRange>()
    private val version = MutableStateFlow(0L)
    val changes: StateFlow<Long> = version.asStateFlow()

    operator fun get(path: String): TextRange = ranges[path] ?: TextRange.Zero

    operator fun set(path: String, range: TextRange) {
        if (ranges[path] == range) return
        ranges[path] = range
        version.update { it + 1 }
    }

    fun remove(path: String) { ranges.remove(path) }

    fun rename(from: String, to: String) {
        ranges.remove(from)?.let { ranges[to] = it }
    }
}
