package dev.easyide.app.ui.screens.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import dev.easyide.app.ui.screens.workspace.edit.TextState

/**
 * A request to bring [offset] of [path] into view, only if it is off screen: stepping through
 * nearby find matches must not jump the view. [offset] refers to text of [textLength] chars: the
 * surface waits for that text to be laid out before measuring. [id] makes two identical
 * requests distinct.
 */
data class RevealRequest(val path: String, val offset: Int, val textLength: Int, val id: Long)

/**
 * What the editing surface shares with commands, find and history, hoisted like
 * [EditorSelections] so the same calls work from the current text field and from the
 * virtualised editor that replaces it (decision 0018).
 *
 *  - **History**: [onUserEdit] is the one entry for typed edits (with the exact selections),
 *    [onContentChanged] catches every other content change (language server, extension) at
 *    the workspace's single choke point, and [undo]/[redo] apply a step through [setContent].
 *  - **Programmatic edits** ([replaceText]) that must be one undo step: replace-all, replace-one.
 *  - **Signals to the surface**: reveal a caret, take focus, drop an IME composition that an
 *    undo just invalidated ([generation]).
 *
 * Main thread, like the state it wraps.
 */
class EditorSession(
    private val selections: EditorSelections,
    private val contentOf: (path: String) -> String?,
    private val setContent: (path: String, text: String) -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    val histories: EditHistories = EditHistories(),
) {
    /** The latest reveal request; the surface applies it and calls [revealHandled]. */
    var reveal by mutableStateOf<RevealRequest?>(null)
        private set

    /** Bumped to move keyboard focus into the editor (closing the find bar returns typing there). */
    var focusTicks by mutableIntStateOf(0)
        private set

    private val generations = mutableStateMapOf<String, Int>()
    private var nextRevealId = 0L

    /** Changes whenever [path]'s text or caret was set from outside the surface (undo, redo, replace). */
    fun generation(path: String): Int = generations[path] ?: 0

    fun canUndo(path: String): Boolean = histories.canUndo(path)

    fun canRedo(path: String): Boolean = histories.canRedo(path)

    /** A user edit with the selections around it, applied by the surface (typing, IME, paste). */
    fun onUserEdit(path: String, before: TextState, after: TextState) {
        histories.record(path, before, after, nowMs())
    }

    /**
     * [path]'s buffer went from [old] to [new] by some route the history has not seen. Called by
     * the workspace for every content change, so a format, a rename apply or a snippet insert
     * lands as one step with the caret carried across it.
     */
    fun onContentChanged(path: String, old: String, new: String) {
        if (histories.isCurrent(path, new)) return
        val range = selections[path]
        val before = TextState(old, range.start.coerceIn(0, old.length), range.end.coerceIn(0, old.length))
        histories.record(path, before, EditHistories.carried(before, new), nowMs())
    }

    fun undo(path: String): Boolean = applyStep(path) { current -> histories.undo(path, current) }

    fun redo(path: String): Boolean = applyStep(path) { current -> histories.redo(path, current) }

    /** Replaces [path]'s whole text with [text] and [selection] as one undo step. */
    fun replaceText(path: String, text: String, selection: TextRange) {
        val current = contentOf(path) ?: return
        val range = selections[path]
        val before = TextState(current, range.start.coerceIn(0, current.length), range.end.coerceIn(0, current.length))
        histories.record(path, before, TextState(text, selection.start, selection.end), nowMs())
        adopt(path, text, selection)
    }

    /** Selects [range] in [path] and brings it into view. */
    fun select(path: String, range: TextRange) {
        selections[path] = range
        generations[path] = generation(path) + 1
        reveal = RevealRequest(path, range.start, contentOf(path)?.length ?: return, nextRevealId++)
    }

    fun revealHandled(request: RevealRequest) {
        if (reveal == request) reveal = null
    }

    fun focusEditor() {
        focusTicks++
    }

    fun onTabClosed(path: String) {
        histories.remove(path)
        generations.remove(path)
    }

    fun onRenamed(from: String, to: String) {
        histories.rename(from, to)
        generations.remove(from)
    }

    private fun applyStep(path: String, step: (current: String) -> TextState?): Boolean {
        val current = contentOf(path) ?: return false
        val state = step(current) ?: return false
        adopt(path, state.text, TextRange(state.selectionStart, state.selectionEnd))
        return true
    }

    private fun adopt(path: String, text: String, selection: TextRange) {
        setContent(path, text)
        select(path, selection)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
