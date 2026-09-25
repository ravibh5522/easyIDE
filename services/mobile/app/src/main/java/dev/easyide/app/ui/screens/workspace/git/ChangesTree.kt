package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.sandbox.git.GitChange

/** One row of the changes as a tree: a folder (possibly several joined, `a/b/c`) or a changed file under it. */
internal sealed interface ChangeItem {
    val level: Int

    /** [path] is the deepest folder of the chain and the collapse key; [label] is the chain as shown. */
    data class Folder(val path: String, val label: String, override val level: Int) : ChangeItem
    data class File(val change: GitChange, override val level: Int) : ChangeItem
}

private class DirNode(val name: String) {
    val dirs = sortedMapOf<String, DirNode>()
    val files = mutableListOf<GitChange>()
}

/**
 * The tree view of a change list, flattened to rows: folders first then files, each sorted by name, and a
 * folder holding only one folder joined with it (`src/main/kotlin`) as VS Code's compact folders do. Folders in
 * [collapsed] show no children.
 */
internal fun flattenChanges(changes: List<GitChange>, collapsed: Set<String>): List<ChangeItem> {
    val root = DirNode("")
    for (change in changes) {
        var node = root
        if (change.directory.isNotEmpty()) change.directory.split('/').forEach { node = node.dirs.getOrPut(it) { DirNode(it) } }
        node.files += change
    }
    val out = ArrayList<ChangeItem>(changes.size)
    fun emit(node: DirNode, level: Int, prefix: String) {
        for (dir in node.dirs.values) {
            var deepest = dir
            var label = dir.name
            var path = prefix + dir.name
            while (deepest.files.isEmpty() && deepest.dirs.size == 1) {
                deepest = deepest.dirs.values.single()
                label += "/" + deepest.name
                path += "/" + deepest.name
            }
            out += ChangeItem.Folder(path, label, level)
            if (path !in collapsed) emit(deepest, level + 1, "$path/")
        }
        node.files.sortedBy { it.name }.forEach { out += ChangeItem.File(it, level) }
    }
    emit(root, 0, "")
    return out
}
