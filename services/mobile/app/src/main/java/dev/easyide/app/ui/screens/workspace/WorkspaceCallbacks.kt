package dev.easyide.app.ui.screens.workspace

import dev.easyide.sandbox.files.FileNode

/**
 * Every action the workspace can raise, in one holder.
 *
 * Passed as a single parameter because the screen needs ~18 callbacks; as a
 * flat parameter list they become impossible to read at the call site and
 * trivial to mis-order (two adjacent `(String) -> Unit` are interchangeable to
 * the compiler but not to the app).
 */
data class WorkspaceCallbacks(
    val onFileOpened: (FileNode) -> Unit,
    val onDirectoryToggled: (FileNode) -> Unit,
    val onRefreshTree: () -> Unit,

    val onTabSelected: (String) -> Unit,
    val onTabClosed: (String) -> Unit,
    val onContentChanged: (String, String) -> Unit,
    val onTogglePreview: () -> Unit,
    val onSave: () -> Unit,
    /** Saves the given tabs, then runs the callback only if every save succeeded. */
    val onSaveTabs: (Collection<String>, () -> Unit) -> Unit,

    val onCreateFile: (String, String) -> Unit,
    val onCreateFolder: (String, String) -> Unit,
    val onRename: (FileNode, String) -> Unit,
    val onDelete: (FileNode) -> Unit,
    val onCopyToClipboard: (FileNode, Boolean) -> Unit,
    val onPaste: (String) -> Unit,
    val absolutePathOf: (FileNode) -> String,

    val onNewTerminal: () -> Unit,
    val onSelectTerminal: (String) -> Unit,
    val onCloseTerminal: (String) -> Unit,
    val onRenameTerminal: (String, String) -> Unit,

    val onInstallLinux: () -> Unit,
    val onStatusShown: () -> Unit,
    val onBack: () -> Unit,
)
