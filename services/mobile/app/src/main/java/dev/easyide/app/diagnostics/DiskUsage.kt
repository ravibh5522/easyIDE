package dev.easyide.app.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Disk usage the way the Storage section reports it: the summed apparent size of regular
 * files. Symbolic links are never followed. A rootfs is full of absolute symlinks that only
 * mean something inside the guest (`/etc/localtime -> /usr/share/zoneinfo/...`), and on the
 * host they point at unrelated paths: following them would count data that is not the
 * environment's. A link itself counts as zero. Hard links are counted once per name, so a
 * rootfs reads slightly larger than `du` would say.
 *
 * Entries that vanish or cannot be read mid-walk (a concurrent apt run, a mode-000
 * directory the sandbox created) are skipped: usage is an estimate for display, not an
 * accounting the app acts on.
 */
object DiskUsage {
    /** Bytes under [path] (a file or a directory); 0 when it does not exist. */
    fun bytesOf(path: File): Long {
        var total = 0L
        Files.walkFileTree(path.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) total += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return total
    }

    /** Bytes of the entries directly inside [dir], except those [keep] names. */
    fun bytesOfContents(dir: File, keep: (String) -> Boolean = { false }): Long =
        dir.listFiles().orEmpty().filterNot { keep(it.name) }.sumOf { bytesOf(it) }
}
