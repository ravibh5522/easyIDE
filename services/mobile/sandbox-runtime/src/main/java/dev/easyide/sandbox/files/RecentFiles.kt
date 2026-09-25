package dev.easyide.sandbox.files

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** A file and when it last changed on disk. */
data class RecentFile(val relativePath: String, val lastModifiedEpochMs: Long)

/**
 * The most recently modified files of a project, for the Home detail pane.
 *
 * Modification time is the honest signal available without a workspace open:
 * the editor keeps no history of opened files. Dependency, build and VCS
 * directories are skipped - their churn (an `npm install`, a compile) would
 * otherwise bury the files the user actually edited - and the walk stops after
 * [MAX_VISITED_FILES] so a huge tree costs a bounded amount of I/O.
 */
object RecentFiles {

    const val MAX_VISITED_FILES = 5_000

    /** Directory names never descended into, at any depth. Dot-directories are skipped as well. */
    val SKIPPED_DIRECTORIES: Set<String> = setOf(
        "node_modules", "build", "dist", "target", "out", "__pycache__", "venv", "vendor", "Pods",
    )

    /** Newest first; ties broken by path so the order is stable. Symlinks are never followed. */
    fun scan(root: File, limit: Int): List<RecentFile> {
        if (!root.isDirectory) return emptyList()
        val base = root.toPath()
        val found = ArrayList<RecentFile>()
        var visited = 0

        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == base) return FileVisitResult.CONTINUE
                val name = dir.fileName.toString()
                return if (name.startsWith('.') || name in SKIPPED_DIRECTORIES) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink || !attrs.isRegularFile) return FileVisitResult.CONTINUE
                found += RecentFile(base.relativize(file).toString(), attrs.lastModifiedTime().toMillis())
                visited++
                return if (visited >= MAX_VISITED_FILES) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            // A file that vanishes or is unreadable mid-walk is simply not "recent".
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })

        return found
            .sortedWith(compareByDescending<RecentFile> { it.lastModifiedEpochMs }.thenBy { it.relativePath })
            .take(limit)
    }
}
