package dev.easyide.app.ui.shell.workspace

import android.view.KeyEvent
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.IntOffset
import com.termux.terminal.TerminalSession
import dev.easyide.app.ui.commands.CommandRegistry
import dev.easyide.app.ui.screens.workspace.EditorSelections
import dev.easyide.app.ui.screens.workspace.GitPanelState
import dev.easyide.app.ui.screens.workspace.InlineEditSpec
import dev.easyide.app.ui.screens.workspace.SourceControlCallbacks
import dev.easyide.app.ui.screens.workspace.WorkspaceCallbacks
import dev.easyide.app.ui.screens.workspace.WorkspaceEditing
import dev.easyide.app.ui.screens.workspace.WorkspaceUiState
import dev.easyide.app.ui.screens.workspace.decor.DecorationRegistry
import dev.easyide.app.ui.shell.DocumentOpener
import dev.easyide.app.ui.screens.workspace.ext.WorkspaceContributions
import dev.easyide.app.ui.screens.workspace.ext.WorkspaceExtensionHost
import dev.easyide.app.ui.screens.workspace.lsp.WorkspaceLspController
import dev.easyide.app.ui.screens.workspace.session.WorkspaceSessionUi
import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.app.ui.screens.workspace.FileAction
import dev.easyide.sandbox.files.FileNode

/** What the panels and documents of a workspace ask of the screen that owns its dialogs and overlays. */
class WorkspaceActions(
    /** A long press or right click on a tree row: the row and the window position of the press. */
    val onNodeMenu: (FileNode, IntOffset) -> Unit,
    /** An explorer action from a key on the tree (F2, Delete, Ctrl+C/X/V), the same as choosing it in the row's menu. */
    val onNodeAction: (FileAction, FileNode) -> Unit,
    /** How a row puts a document on the stage (a change row opening its diff, a commit's files). */
    val documents: DocumentOpener,
    val onNewFile: () -> Unit,
    val onNewFolder: () -> Unit,
    /** The name being typed into the tree (new file or folder, rename), and what ends it. */
    val inline: InlineEditSpec,
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
