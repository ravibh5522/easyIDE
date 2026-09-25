package dev.easyide.app.ui.screens.workspace.files

import dev.easyide.sandbox.files.FileNode
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Which explorer rows to show. Both switches are persisted settings (`explorer.hideHiddenFiles`,
 * `explorer.respectGitignore`); [ignore] holds the `.gitignore` files read so far.
 */
data class TreeFilter(
    val hideHidden: Boolean = false,
    val respectIgnore: Boolean = false,
    val ignore: IgnoreIndex = IgnoreIndex.EMPTY,
) {
    fun shows(node: FileNode): Boolean =
        !(hideHidden && node.name.startsWith('.')) &&
            !(respectIgnore && ignore.isIgnored(node.relativePath, node.isDirectory))

    companion object {
        val SHOW_ALL = TreeFilter()
    }
}

/**
 * Reads the `.gitignore` files that appear in directory listings into an [IgnoreIndex].
 * [readText] is the only I/O, so tests drive it from a map.
 */
class IgnoreLoader(private val readText: suspend (path: String) -> String?) {

    /**
     * [index] with [dir]'s `.gitignore` (re)read if [children] lists one, or dropped if it
     * vanished. Re-reading on every listing keeps the rules current after the file is edited.
     */
    suspend fun refresh(index: IgnoreIndex, dir: String, children: List<FileNode>): IgnoreIndex {
        val file = children.firstOrNull { it.name == GITIGNORE && !it.isDirectory }
        if (file == null) return if (dir in index.directories) index.without(dir) else index
        val text = readText(file.relativePath) ?: return index
        return index.with(dir, GitignoreRules.parse(text))
    }

    companion object {
        const val GITIGNORE = ".gitignore"
    }
}

/** The files of a project for quick open. [truncated]: the walk stopped at [FileIndexer.MAX_FILES]. */
data class FileIndex(val paths: List<String>, val truncated: Boolean) {
    companion object {
        val EMPTY = FileIndex(emptyList(), truncated = false)
    }
}

/**
 * Walks a project tree for quick open, off the main thread (the caller picks the dispatcher).
 *
 * Skips what a finder should never offer: directories in [ALWAYS_SKIPPED_DIRECTORIES]
 * (the `.git` object store), everything a `.gitignore` excludes (git's own rule: an ignored
 * directory is not entered, so `node_modules/` costs one listing), and, when [hideHidden],
 * dotfiles. Bounded by [MAX_FILES] and [MAX_DEPTH] so a symlink loop or a vendored monorepo
 * cannot run away.
 */
class FileIndexer(
    private val list: suspend (dir: String) -> List<FileNode>?,
    private val ignoreLoader: IgnoreLoader,
) {

    suspend fun build(hideHidden: Boolean): FileIndex {
        val files = ArrayList<String>()
        var ignore = IgnoreIndex.EMPTY
        val pending = ArrayDeque<Pair<String, Int>>().apply { add("" to 0) }
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (dir, depth) = pending.removeFirst()
            val children = list(dir) ?: continue
            ignore = ignoreLoader.refresh(ignore, dir, children)
            for (node in children) {
                if (node.isDirectory && node.name in ALWAYS_SKIPPED_DIRECTORIES) continue
                if (hideHidden && node.name.startsWith('.')) continue
                if (ignore.isIgnored(node.relativePath, node.isDirectory)) continue
                if (node.isDirectory) {
                    if (depth < MAX_DEPTH) pending.add(node.relativePath to depth + 1)
                } else {
                    if (files.size == MAX_FILES) return FileIndex(files, truncated = true)
                    files += node.relativePath
                }
            }
        }
        return FileIndex(files, truncated = false)
    }

    companion object {
        const val MAX_FILES = 50_000
        const val MAX_DEPTH = 32
        val ALWAYS_SKIPPED_DIRECTORIES = setOf(".git")
    }
}
