package dev.easyide.app.ui.screens.workspace.layout

import dev.easyide.sandbox.files.FileNode

/** Path arithmetic for revealing a file in the explorer tree. */
object TreePaths {

    /** Directories above [path], outermost first: `a/b/c.kt` -> `a`, `a/b`. Empty for a root file. */
    fun ancestors(path: String): List<String> {
        val parts = path.split('/')
        return (1 until parts.size).map { parts.take(it).joinToString("/") }
    }

    /**
     * The row index of [path] in the explorer as it is drawn (a directory's children follow it
     * while expanded), or -1 when it is not visible.
     */
    fun visibleIndex(
        path: String,
        roots: List<FileNode>,
        expanded: Set<String>,
        children: Map<String, List<FileNode>>,
    ): Int {
        var index = 0
        fun walk(nodes: List<FileNode>): Int {
            for (node in nodes) {
                if (node.relativePath == path) return index
                index++
                if (node.isDirectory && node.relativePath in expanded) {
                    val hit = walk(children[node.relativePath].orEmpty())
                    if (hit >= 0) return hit
                }
            }
            return -1
        }
        return walk(roots)
    }
}
