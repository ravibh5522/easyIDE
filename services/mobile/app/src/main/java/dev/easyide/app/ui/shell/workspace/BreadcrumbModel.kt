package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.ui.screens.workspace.lsp.SymbolRow
import dev.easyide.lsp.protocol.Position

/** A folder or file of the path breadcrumb: [path] is project-relative, and [parent] the folder whose children are its siblings. */
data class PathCrumb(val name: String, val path: String, val parent: String, val isFile: Boolean)

/** An entry of a folder as the breadcrumb menu lists it. */
data class DirEntry(val name: String, val path: String, val isDir: Boolean)

/** The pure part of the breadcrumb row: what its segments are, and what a menu under one lists. */
object BreadcrumbModel {

    /** One crumb per folder and the file, from the project root; the root itself is not a crumb. */
    fun path(relativePath: String): List<PathCrumb> {
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        return parts.indices.map { i ->
            PathCrumb(parts[i], parts.take(i + 1).joinToString("/"), parts.take(i).joinToString("/"), isFile = i == parts.lastIndex)
        }
    }

    /**
     * The symbols whose body holds the caret, outermost first. [rows] are the outline in document order
     * with their depth, so the ones that contain the caret nest by construction.
     */
    fun symbolsAt(rows: List<SymbolRow>, text: String, offset: Int): List<SymbolRow> {
        if (rows.isEmpty()) return emptyList()
        val at = positionOf(text, offset)
        return rows.filter { it.span?.contains(at) == true }.sortedBy { it.depth }
    }

    fun positionOf(text: String, offset: Int): Position {
        val end = offset.coerceIn(0, text.length)
        var line = 0
        var lineStart = 0
        for (i in 0 until end) if (text[i] == '\n') { line++; lineStart = i + 1 }
        return Position(line, end - lineStart)
    }

    /** The direct children of [dir] among the project's file [index] (paths), folders first, each group by name. */
    fun children(index: List<String>, dir: String): List<DirEntry> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val entries = LinkedHashMap<String, DirEntry>()
        for (file in index) {
            if (!file.startsWith(prefix)) continue
            val rest = file.substring(prefix.length)
            val slash = rest.indexOf('/')
            val name = if (slash < 0) rest else rest.substring(0, slash)
            if (name.isNotEmpty()) entries.putIfAbsent(name, DirEntry(name, prefix + name, isDir = slash >= 0))
        }
        return entries.values.sortedWith(compareBy<DirEntry> { !it.isDir }.thenBy { it.name.lowercase() })
    }
}
