package dev.easyide.app.ui.shell.workspace

import android.view.KeyEvent
import androidx.compose.runtime.compositionLocalOf
import com.termux.terminal.TerminalSession
import dev.easyide.app.ui.commands.CommandRegistry
import dev.easyide.app.ui.screens.workspace.EditorSelections
import dev.easyide.app.ui.screens.workspace.GitPanelState
import dev.easyide.app.ui.screens.workspace.SourceControlCallbacks
import dev.easyide.app.ui.screens.workspace.WorkspaceCallbacks
import dev.easyide.app.ui.screens.workspace.WorkspaceEditing
import dev.easyide.app.ui.screens.workspace.WorkspaceUiState
import dev.easyide.app.ui.screens.workspace.decor.DecorationRegistry
import dev.easyide.app.ui.screens.workspace.ext.WorkspaceContributions
import dev.easyide.app.ui.screens.workspace.ext.WorkspaceExtensionHost
import dev.easyide.app.ui.screens.workspace.lsp.WorkspaceLspController
import dev.easyide.app.ui.screens.workspace.session.WorkspaceSessionUi
import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.sandbox.files.FileNode

/** What the panels and documents of a workspace ask of the screen that owns its dialogs and overlays. */
class WorkspaceActions(
    val onNodeMenu: (FileNode) -> Unit,
    val onNewFile: () -> Unit,
    val onNewFolder: () -> Unit,
    val onRenameTerminal: (id: String, title: String) -> Unit,
    /** Close a file document with the unsaved-changes guard, not the raw view model close. */
    val closeFile: (path: String) -> Unit,
    val onTerminalKey: (KeyEvent) -> Boolean,
    val onTerminalRowKey: (KeyAction, TerminalSession) -> Unit,
    val onEditorRowKey: (KeyAction) -> Unit,
    val onEditorFocus: (Boolean) -> Unit,
    val onTerminalFocus: (Boolean) -> Unit,
    val onFindFieldFocus: (Boolean) -> Unit,
)

/**
 * Everything a workspace panel or document draws on, read through [LocalWorkspaceEnv] so the panel
 * bindings are plain composables in a registry and need no parameters of their own. Rebuilt when the
 * screen recomposes, which is when any of it changed.
 */
class WorkspaceEnv(
    val projectName: String,
    val ui: WorkspaceUiState,
    val callbacks: WorkspaceCallbacks,
    val git: GitPanelState,
    val gitCallbacks: SourceControlCallbacks,
    val lsp: WorkspaceLspController,
    val decorations: DecorationRegistry,
    val selections: EditorSelections,
    val session: WorkspaceSessionUi,
    val editing: WorkspaceEditing,
    val contributions: WorkspaceContributions,
    val commands: CommandRegistry,
    val extensionHost: WorkspaceExtensionHost,
    val actions: WorkspaceActions,
)

val LocalWorkspaceEnv = compositionLocalOf<WorkspaceEnv> { error("no workspace around this composable") }
