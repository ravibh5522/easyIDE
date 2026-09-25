package dev.easyide.sandbox.files

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Recursive delete and copy that never follow symbolic links.
 *
 * Kotlin's `File.deleteRecursively` and `copyRecursively` descend into a
 * symlink that points at a directory. A project tree is user-controlled code
 * (an npm `node_modules/.bin`, a `ln -s ../../environments/<id>/rootfs` typed
 * in the terminal), so following links here could delete - or duplicate -
 * something outside the project, including another environment's rootfs. A
 * link is treated as the file it is: removed or copied as a link, never
 * traversed.
 */
object SafeTree {

    /** Removes [root] and everything under it. A missing [root] is not an error. */
    fun deleteRecursively(root: File) {
        val start = root.toPath()
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Read-only directories (Go module caches, git objects) refuse child deletion.
                dir.toFile().setWritable(true)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /**
     * Copies [source] to [target] (which must not exist), keeping symlinks as
     * symlinks and the executable bit on files.
     */
    fun copyRecursively(source: File, target: File) {
        val from = source.toPath()
        val to = target.toPath()
        Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val destination = to.resolve(from.relativize(file).toString())
                Files.copy(file, destination, LinkOption.NOFOLLOW_LINKS)
                if (!attrs.isSymbolicLink && file.toFile().canExecute()) destination.toFile().setExecutable(true, false)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
