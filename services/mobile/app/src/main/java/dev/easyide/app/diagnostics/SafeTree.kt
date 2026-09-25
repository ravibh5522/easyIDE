package dev.easyide.app.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** A deletion was refused because its target resolves outside the directory it may touch. */
class RefusedDeletion(message: String) : IOException(message)

/** What a deletion did: bytes actually removed, and entries that could not be removed. */
data class Deleted(val bytes: Long, val failedEntries: Int)

/**
 * Deleting inside a rootfs from the host, safely. Rootfs symlinks are absolute *guest* paths;
 * followed on the host they can point at the app's projects or at other environments, so:
 *
 * - links are deleted as links and never followed (`walkFileTree` without `FOLLOW_LINKS`);
 * - a target whose real path (parent symlinks resolved) is outside the base it was expected
 *   in is refused before anything is removed;
 * - a target that is itself a link, or is missing, is treated as empty: there is nothing of
 *   the base's to delete through it.
 *
 * This guards against mistakes and stray links, not against a hostile process swapping paths
 * mid-walk: sandboxed code runs as the app's own UID and could delete these files directly
 * (decision 0002).
 */
object SafeTree {
    /**
     * The real path of [dir] when it is an existing directory (not a link) inside [base],
     * null when there is nothing to walk.
     *
     * @throws RefusedDeletion if [dir] resolves outside [base].
     */
    fun resolve(dir: File, base: File): Path? {
        val path = dir.toPath()
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return null
        val real = path.toRealPath()
        if (!real.startsWith(base.toPath().toRealPath())) {
            throw RefusedDeletion("Refusing to touch ${dir.path}: it resolves to $real, outside ${base.path}")
        }
        return real
    }

    /** Bytes [deleteContents] would remove for the same arguments. */
    fun contentsBytes(dir: File, base: File, keep: (String) -> Boolean = { false }): Long {
        val real = resolve(dir, base) ?: return 0
        return DiskUsage.bytesOfContents(real.toFile(), keep)
    }

    /**
     * Deletes everything inside [dir] except the top-level names [keep] accepts, keeping [dir]
     * itself. Undeletable entries are counted in [Deleted.failedEntries] and skipped, so one
     * stubborn file does not stop the rest of a cache from being cleared.
     *
     * @throws RefusedDeletion if [dir] resolves outside [base].
     */
    fun deleteContents(dir: File, base: File, keep: (String) -> Boolean = { false }): Deleted {
        val real = resolve(dir, base) ?: return Deleted(0, 0)
        val visitor = DeletingVisitor()
        Files.newDirectoryStream(real).use { children ->
            for (child in children) {
                if (!keep(child.fileName.toString())) Files.walkFileTree(child, visitor)
            }
        }
        return Deleted(visitor.bytes, visitor.failed)
    }

    private class DeletingVisitor : SimpleFileVisitor<Path>() {
        var bytes = 0L
        var failed = 0

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            val size = if (attrs.isRegularFile) attrs.size() else 0L
            if (tryDelete(file)) bytes += size
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            failed += 1
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) failed += 1 else tryDelete(dir)
            return FileVisitResult.CONTINUE
        }

        private fun tryDelete(path: Path): Boolean =
            try {
                Files.delete(path)
                true
            } catch (stubborn: IOException) {
                failed += 1
                false
            }
    }
}
