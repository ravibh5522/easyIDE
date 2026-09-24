package dev.easyide.app.extensions.install

import dev.easyide.app.extensions.ExtensionUiPolicy
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.PackagePaths
import dev.easyide.extensions.manifest.ZipFormatException
import dev.easyide.extensions.manifest.ZipSymlinks
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Why a package was refused before validation; [message] is shown on the install sheet. */
class PackageRefused(message: String) : IOException(message)

/** A picked folder, abstracted from SAF so the copy rules run in JVM tests. */
interface FolderNode {
    val name: String
    val isDirectory: Boolean
    fun children(): List<FolderNode>

    /** I/O boundary: throws IOException. */
    fun open(): InputStream
}

/**
 * Copies a picked `.easyext` zip or folder into a staging directory, enforcing the
 * sdk-reference package rules that must hold before anything is parsed: relative
 * `/`-separated names without `..`, no symlink entries, and the size limits counted while
 * copying (never trusted from zip headers, so a zip bomb stops at the limit). Duplicate
 * names, nested archives and per-file limits are checked again on disk by
 * `PackageLayoutReader`, the second line.
 */
class PackageUnpacker(private val limits: () -> PackageLimits) {

    /** Stores the archive at [archive] (bounded by `packageMb`) and unpacks it into [dest]. */
    fun unpackZip(input: InputStream, archive: File, dest: File) {
        val l = limits()
        archive.parentFile?.mkdirs()
        archive.outputStream().use { out -> copyBounded(input, out, l.packageBytes, "package") }
        val links = try { ZipSymlinks.find(archive) } catch (e: ZipFormatException) { throw PackageRefused(e.message ?: "malformed zip archive") }
        links.firstOrNull()?.let { throw PackageRefused("symbolic link entry: $it") }
        var total = 0L
        try {
            ZipFile(archive).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val path = PackagePaths.normalize(entry.name) ?: throw PackageRefused("invalid entry name: ${entry.name}")
                    val target = File(dest, path)
                    target.parentFile?.mkdirs()
                    val written = zip.getInputStream(entry).use { src ->
                        target.outputStream().use { copyBounded(src, it, minOf(l.fileBytes, l.unpackedBytes - total), path) }
                    }
                    total += written
                }
            }
        } catch (e: ZipException) {
            throw PackageRefused("not a valid .easyext archive: ${e.message}")
        }
    }

    /** Copies the folder tree [root] into [dest] under the same name and size rules. */
    fun copyFolder(root: FolderNode, dest: File) {
        val l = limits()
        var total = 0L
        fun walk(node: FolderNode, prefix: String, depth: Int) {
            if (depth > ExtensionUiPolicy.MAX_FOLDER_DEPTH) throw PackageRefused("folder nesting deeper than ${ExtensionUiPolicy.MAX_FOLDER_DEPTH}")
            for (child in node.children()) {
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                val path = PackagePaths.normalize(rel) ?: throw PackageRefused("invalid file name: $rel")
                if (child.isDirectory) { walk(child, path, depth + 1); continue }
                val target = File(dest, path)
                target.parentFile?.mkdirs()
                total += child.open().use { src ->
                    target.outputStream().use { copyBounded(src, it, minOf(l.fileBytes, l.unpackedBytes - total), path) }
                }
            }
        }
        dest.mkdirs()
        walk(root, "", 0)
    }

    private fun copyBounded(input: InputStream, out: java.io.OutputStream, limit: Long, what: String): Long {
        val buffer = ByteArray(ExtensionUiPolicy.COPY_BUFFER_BYTES)
        var copied = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return copied
            copied += n
            if (copied > limit) throw PackageRefused("$what exceeds the size limit ($limit bytes)")
            out.write(buffer, 0, n)
        }
    }
}
