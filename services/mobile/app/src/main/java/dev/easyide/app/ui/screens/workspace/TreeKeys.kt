package dev.easyide.app.ui.screens.workspace

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.easyide.sandbox.files.FileNode
import dev.easyide.sandbox.git.GitChangeType

/** What decorates rows of the tree beyond their names: files with unsaved edits, git changes by path, and the folders that hold a change. */
internal class TreeMarks(val dirty: Set<String>, val changes: Map<String, GitChangeType>) {
    val changedDirs: Set<String> = buildSet {
        changes.keys.forEach { path ->
            var dir = path.substringBeforeLast('/', "")
            while (dir.isNotEmpty() && add(dir)) dir = dir.substringBeforeLast('/', "")
        }
    }
}

/** The folders that are open, in tree order, so "collapse all" can close each through the one toggle the tree already has. */
internal fun WorkspaceUiState.expandedFolders(): List<FileNode> {
    val out = ArrayList<FileNode>()
    fun walk(nodes: List<FileNode>) {
        for (node in nodes) {
            if (node.isDirectory && node.relativePath in expandedDirs) {
                out += node
                walk(childrenByDir[node.relativePath].orEmpty())
            }
        }
    }
    walk(tree)
    return out
}

/**
 * The keys the explorer answers to when a row has focus (hardware keyboard): F2 renames, Delete deletes,
 * Ctrl+C and Ctrl+X copy and cut, Ctrl+V pastes into a focused folder. Anything else, and any key while no
 * row is focused, is left alone; true means the key was used.
 */
internal fun treeKey(event: KeyEvent, target: FileNode?, canPaste: Boolean, run: (FileAction, FileNode) -> Unit): Boolean {
    if (target == null || event.type != KeyEventType.KeyDown) return false
    val action = when {
        event.key == Key.F2 -> FileAction.RENAME
        event.key == Key.Delete -> FileAction.DELETE
        event.isCtrlPressed && event.key == Key.C -> FileAction.COPY
        event.isCtrlPressed && event.key == Key.X -> FileAction.CUT
        event.isCtrlPressed && event.key == Key.V && canPaste && target.isDirectory -> FileAction.PASTE
        else -> return false
    }
    run(action, target)
    return true
}
