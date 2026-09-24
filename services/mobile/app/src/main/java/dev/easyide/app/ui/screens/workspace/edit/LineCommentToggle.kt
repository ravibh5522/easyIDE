package dev.easyide.app.ui.screens.workspace.edit

import androidx.compose.ui.text.TextRange
import dev.easyide.app.ui.screens.workspace.EditorSelections
import dev.easyide.app.ui.screens.workspace.WorkspaceUiState
import dev.easyide.app.ui.screens.workspace.ext.EditorBuffers
import dev.easyide.app.ui.screens.workspace.syntax.LanguageConfigs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Toggle Line Comment" command (VS Code `editor.action.commentLine`) on the active tab:
 * [EditCommands.toggleComment] with the tab's language configuration, applied to the buffer
 * and the selection mapped through the edit.
 *
 * The configuration is read on IO (the first use of a language parses an asset); the tab is
 * looked up again afterwards, so text typed in between is never overwritten by a stale copy.
 */
class LineCommentToggle(
    private val state: StateFlow<WorkspaceUiState>,
    private val selections: EditorSelections,
    private val buffers: EditorBuffers,
    private val scope: CoroutineScope,
) {
    fun toggle() {
        val tab = state.value.activeTab?.takeIf { it.editable } ?: return
        val path = tab.relativePath
        scope.launch(Dispatchers.Main.immediate) {
            val config = withContext(Dispatchers.IO) { LanguageConfigs.forFile(tab.name) }
            val text = state.value.openTabs.find { it.relativePath == path }?.takeIf { it.editable }?.content ?: return@launch
            val sel = selections[path]
            val before = TextState(text, sel.start.coerceIn(0, text.length), sel.end.coerceIn(0, text.length))
            val after = EditCommands.toggleComment(before, config)
            if (after == before || !buffers.replaceContent(path, after.text)) return@launch
            selections[path] = TextRange(after.selectionStart, after.selectionEnd)
        }
    }
}
