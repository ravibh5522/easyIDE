package dev.easyide.app.extensions.install

import dev.easyide.app.extensions.ExtensionUiPolicy
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.PackagePaths
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
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
        ZipSymlinks.find(archive).firstOrNull()?.let { throw PackageRefused("symbolic link entry: $it") }
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

/**
 * Finds symlink entries by reading the zip central directory, which `java.util.zip`
 * does not expose: an entry made on Unix (`version made by` host 3) whose external
 * attributes carry `S_IFLNK`. Unpacking would turn such an entry into a regular file,
 * but a package that contains one breaks the layout rules, so it is refused (EXT-02).
 */
internal object ZipSymlinks {
    private const val EOCD_SIG = 0x06054b50
    private const val EOCD64_LOCATOR_SIG = 0x07064b50
    private const val CENTRAL_SIG = 0x02014b50
    private const val EOCD_MIN = 22
    private const val MAX_COMMENT = 0xFFFF
    private const val UNIX_HOST = 3
    private const val S_IFMT = 0xF000
    private const val S_IFLNK = 0xA000
    private const val U16 = 0xFFFF
    private const val U32 = 0xFFFFFFFFL

    /** Names of symlink entries; empty for a zip without any. @throws PackageRefused on a malformed directory. */
    fun find(file: File): List<String> = RandomAccessFile(file, "r").use { raf ->
        val (cdOffset, cdSize) = centralDirectory(raf)
        val cd = ByteArray(cdSize.toInt())
        raf.seek(cdOffset)
        raf.readFully(cd)
        val out = ArrayList<String>()
        var p = 0
        while (p + CENTRAL_FIXED <= cd.size && int32(cd, p) == CENTRAL_SIG) {
            val host = (cd[p + 5].toInt() and 0xFF)
            val nameLen = int16(cd, p + 28)
            val extraLen = int16(cd, p + 30)
            val commentLen = int16(cd, p + 32)
            val attrs = int32(cd, p + 38).toLong() and U32
            if (p + CENTRAL_FIXED + nameLen > cd.size) throw PackageRefused("truncated zip directory")
            if (host == UNIX_HOST && ((attrs ushr 16).toInt() and S_IFMT) == S_IFLNK) {
                out += String(cd, p + CENTRAL_FIXED, nameLen, Charsets.UTF_8)
            }
            p += CENTRAL_FIXED + nameLen + extraLen + commentLen
        }
        out
    }

    private const val CENTRAL_FIXED = 46

    private fun centralDirectory(raf: RandomAccessFile): Pair<Long, Long> {
        val len = raf.length()
        if (len < EOCD_MIN) throw PackageRefused("not a zip archive")
        val tailLen = minOf(len, (EOCD_MIN + MAX_COMMENT).toLong()).toInt()
        val tail = ByteArray(tailLen)
        raf.seek(len - tailLen)
        raf.readFully(tail)
        var eocd = -1
        for (i in tailLen - EOCD_MIN downTo 0) if (int32(tail, i) == EOCD_SIG) { eocd = i; break }
        if (eocd < 0) throw PackageRefused("not a zip archive")
        val size = int32(tail, eocd + 12).toLong() and U32
        val offset = int32(tail, eocd + 16).toLong() and U32
        if (size != U32 && offset != U32) return checked(offset, size, len)
        // Zip64: the locator sits just before the classic record.
        val locator = eocd - 20
        if (locator < 0 || int32(tail, locator) != EOCD64_LOCATOR_SIG) throw PackageRefused("malformed zip64 archive")
        val record = int64(tail, locator + 8)
        val rec = ByteArray(56)
        raf.seek(record)
        raf.readFully(rec)
        return checked(int64(rec, 48), int64(rec, 40), len)
    }

    private fun checked(offset: Long, size: Long, fileLen: Long): Pair<Long, Long> {
        if (offset < 0 || size < 0 || offset + size > fileLen || size > Int.MAX_VALUE) throw PackageRefused("malformed zip directory")
        return offset to size
    }

    private fun int16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) and U16
    private fun int32(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
        ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
    private fun int64(b: ByteArray, i: Int) = (int32(b, i).toLong() and U32) or ((int32(b, i + 4).toLong() and U32) shl 32)
}
