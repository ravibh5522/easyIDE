package dev.easyide.app.ui.screens.workspace.files

/**
 * A project's most-recently-opened files, newest first, capped at [MAX]. Immutable: every
 * operation returns the next list, so it can live in a state flow and be persisted as-is.
 */
data class RecentFiles(val paths: List<String> = emptyList()) {

    fun touched(path: String): RecentFiles = RecentFiles((listOf(path) + paths.filter { it != path }).take(MAX))

    /** [path] and, if it was a directory, everything under it. */
    fun without(path: String): RecentFiles {
        val kept = paths.filterNot { it == path || it.startsWith("$path/") }
        return if (kept.size == paths.size) this else RecentFiles(kept)
    }

    /** [from] renamed to [to], keeping its place; a renamed directory moves everything under it. */
    fun renamed(from: String, to: String): RecentFiles = RecentFiles(
        paths.map { if (it == from) to else if (it.startsWith("$from/")) to + it.removePrefix(from) else it }.distinct(),
    )

    companion object {
        const val MAX = 30
    }
}
