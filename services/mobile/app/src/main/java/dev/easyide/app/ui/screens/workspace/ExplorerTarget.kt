package dev.easyide.app.ui.screens.workspace

import dev.easyide.sandbox.files.FileNode

/**
 * The folder a new file or folder goes into (null: the project root): the folder the user last tapped, or
 * the folder of the file they last tapped, or - with nothing tapped - the folder of the file open in the editor.
 */
internal fun explorerTarget(state: WorkspaceUiState, focus: FileNode?): FileNode? {
    val node = focus ?: state.activeTabPath?.let { findNode(state, it) } ?: return null
    if (node.isDirectory) return node
    val parent = node.relativePath.substringBeforeLast('/', "")
    return if (parent.isEmpty()) null else findNode(state, parent)
}

/** A loaded node by project path: the root list, then every listed directory's children. */
internal fun findNode(state: WorkspaceUiState, path: String): FileNode? =
    state.tree.firstOrNull { it.relativePath == path }
        ?: state.childrenByDir.values.firstNotNullOfOrNull { children -> children.firstOrNull { it.relativePath == path } }
