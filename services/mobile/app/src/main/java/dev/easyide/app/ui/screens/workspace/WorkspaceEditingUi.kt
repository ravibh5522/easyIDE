package dev.easyide.app.ui.screens.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.TextRange
import dev.easyide.app.R
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.ui.commands.Command
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.app.ui.foundation.LocalSettings
import dev.easyide.app.ui.screens.workspace.edit.GoToLine
import dev.easyide.app.ui.screens.workspace.find.GoToLineOverlay
import dev.easyide.app.ui.screens.workspace.quickopen.QuickOpen
import dev.easyide.sandbox.files.FileNode

/**
 * Which editing overlays are up. Plain state, held by the screen; [ownsTextInput] tells the
 * key handler that a text field other than the editor has focus (see [CommandIds.HISTORY]).
 */
class EditingOverlays {
    var quickOpen by mutableStateOf(false)
    var goToLine by mutableStateOf(false)
    var findFieldFocused by mutableStateOf(false)

    val ownsTextInput: Boolean get() = quickOpen || goToLine || findFieldFocused
}

/**
 * Undo/redo, find, replace, go to line and quick open as commands. Like the other command
 * groups they only route: the logic lives in [WorkspaceEditing]. Editing commands are enabled
 * only on a tab the editable surface is showing (not a read-only preview, not rendered markdown).
 */
fun editingCommands(uiState: WorkspaceUiState, editing: WorkspaceEditing, overlays: EditingOverlays): List<Command> {
    val active = uiState.activeTab
    val path = active?.relativePath
    val editable = active != null && active.editable && !(active.isMarkdown && active.showPreview)
    val session = editing.session
    val find = editing.find
    return listOf(
        Command(CommandIds.UNDO, R.string.command_undo, enabled = editable && path != null && session.canUndo(path)) {
            path?.let(session::undo)
        },
        Command(CommandIds.REDO, R.string.command_redo, enabled = editable && path != null && session.canRedo(path)) {
            path?.let(session::redo)
        },
        Command(CommandIds.FIND, R.string.command_find, enabled = editable) { find.open(replace = false) },
        Command(CommandIds.REPLACE, R.string.command_replace, enabled = editable) { find.open(replace = true) },
        // With the bar closed there are no matches to step through, so F3 opens it like Ctrl+F.
        Command(CommandIds.FIND_NEXT, R.string.command_find_next, enabled = editable) {
            if (find.isOpen) find.next() else find.open(replace = false)
        },
        Command(CommandIds.FIND_PREVIOUS, R.string.command_find_previous, enabled = editable) {
            if (find.isOpen) find.previous() else find.open(replace = false)
        },
        Command(CommandIds.GO_TO_LINE, R.string.command_go_to_line, enabled = editable) { overlays.goToLine = true },
        Command(CommandIds.QUICK_OPEN, R.string.command_quick_open) { overlays.quickOpen = true },
    )
}

/** Quick open and go to line, drawn over the workspace while [overlays] says so. */
@Composable
fun EditingOverlayHost(
    overlays: EditingOverlays,
    editing: WorkspaceEditing,
    uiState: WorkspaceUiState,
    onOpenFile: (FileNode) -> Unit,
    onCommands: (query: String) -> Unit,
    onPrefix: (prefix: Char, query: String) -> Boolean,
) {
    if (overlays.quickOpen) {
        val hideHidden = LocalSettings.current[SettingsSchema.explorerHideHidden]
        val index by editing.files.index.collectAsState()
        val indexing by editing.files.indexing.collectAsState()
        val recent by editing.files.recent.collectAsState()
        // Rebuilt when the overlay opens (files may have changed since the last walk) and when the
        // dotfile filter flips while it is open; the previous list stays visible meanwhile.
        LaunchedEffect(hideHidden) { editing.files.refreshIndex(hideHidden) }
        QuickOpen(
            index = index,
            indexing = indexing,
            recent = recent.paths,
            onOpen = { path -> onOpenFile(FileNode(path.substringAfterLast('/'), path, isDirectory = false, sizeBytes = 0)) },
            onDismiss = { overlays.quickOpen = false; editing.session.focusEditor() },
            onCommands = onCommands,
            onPrefix = onPrefix,
        )
    }
    val tab = uiState.activeTab
    if (overlays.goToLine && tab != null) {
        GoToLineOverlay(
            lineCount = LineCount.of(tab.content),
            onGo = { target ->
                val offset = GoToLine.offsetOf(tab.content, target)
                editing.session.select(tab.relativePath, TextRange(offset))
                editing.session.focusEditor()
            },
            onDismiss = { overlays.goToLine = false },
        )
    }
}
