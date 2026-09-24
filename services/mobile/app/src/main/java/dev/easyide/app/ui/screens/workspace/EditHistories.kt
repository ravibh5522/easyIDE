package dev.easyide.app.ui.screens.workspace

import androidx.compose.runtime.mutableStateMapOf
import dev.easyide.app.ui.screens.workspace.decor.OffsetEdit
import dev.easyide.app.ui.screens.workspace.edit.EditHistory
import dev.easyide.app.ui.screens.workspace.edit.HistoryLimits
import dev.easyide.app.ui.screens.workspace.edit.TextState

/**
 * One [EditHistory] per open document, owned by the workspace so history survives tab switches
 * (only the active tab's editor is composed) and recomposition, and is dropped when the tab
 * closes. Serialises access (edits arrive from the editor, language-server results and
 * extension actions) and exposes [canUndo]/[canRedo] as snapshot state, so a command's
 * `enabled` flag and a toolbar button follow the history without polling.
 */
class EditHistories(private val limits: HistoryLimits = HistoryLimits()) {

    private data class Flags(val canUndo: Boolean, val canRedo: Boolean)

    private val histories = HashMap<String, EditHistory>()
    private val flags = mutableStateMapOf<String, Flags>()

    fun canUndo(path: String): Boolean = flags[path]?.canUndo == true

    fun canRedo(path: String): Boolean = flags[path]?.canRedo == true

    @Synchronized
    fun record(path: String, before: TextState, after: TextState, nowMs: Long) {
        val history = histories.getOrPut(path) { EditHistory(before.text, limits) }
        history.record(before, after, nowMs)
        publish(path, history)
    }

    /** True when [text] is exactly what [path]'s history last saw, i.e. recording it again would be a no-op. */
    @Synchronized
    fun isCurrent(path: String, text: String): Boolean = histories[path]?.head == text

    @Synchronized
    fun undo(path: String, current: String): TextState? = step(path) { it.undo(current) }

    @Synchronized
    fun redo(path: String, current: String): TextState? = step(path) { it.redo(current) }

    @Synchronized
    fun remove(path: String) {
        histories.remove(path)
        flags.remove(path)
    }

    @Synchronized
    fun rename(from: String, to: String) {
        histories.remove(from)?.let { histories[to] = it; publish(to, it) }
        flags.remove(from)
    }

    private fun step(path: String, action: (EditHistory) -> TextState?): TextState? {
        val history = histories[path] ?: return null
        return action(history).also { publish(path, history) }
    }

    private fun publish(path: String, history: EditHistory) {
        val next = Flags(history.canUndo, history.canRedo)
        if (flags[path] != next) flags[path] = next
    }

    companion object {
        /**
         * [before] after an edit that only yielded [newText] (no selection was reported, as
         * with language-server and extension edits): the selection carried across the change
         * - unchanged before it, shifted after it, at the end of the new text inside it.
         */
        fun carried(before: TextState, newText: String): TextState {
            val edit = OffsetEdit.between(before.text, newText) ?: return before.copy(text = newText)
            return TextState(newText, edit.mapAfter(before.selectionStart), edit.mapAfter(before.selectionEnd))
        }
    }
}
