package dev.easyide.sandbox.bootstrap

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/**
 * Streaming gzip+tar extractor built for Android app-private storage.
 *
 * The platform `tar` cannot unpack a distro rootfs here: Android denies
 * `link(2)` in app data, so it aborts on the first hard link
 * (`can't link 'usr/bin/perl5.38.2' -> 'usr/bin/perl': Permission denied`).
 * A real rootfs is full of them.
 *
 * This extractor therefore **materialises hard links as copies** and creates
 * symlinks properly, which is what makes a rootfs usable on a real device.
 * Costs some duplicated bytes; correctness is worth more than the megabytes.
 *
 * It also understands the **pax** format the Ubuntu base images actually use -
 * every entry there is preceded by an extended header carrying its timestamps.
 * Treating those as ordinary files wrote a `PaxHeaders` directory next to
 * almost every real one, and dpkg aborts the moment it finds one inside
 * `/etc/dpkg/dpkg.cfg.d`, which broke every `apt-get install`.
 */
class TarGzExtractor {

    data class Result(val entries: Int, val hardLinksCopied: Int, val symlinks: Int)

    /**
     * @throws IOException if the archive is malformed or escapes [destination].
     */
    fun extract(source: InputStream, destination: File): Result {
        if (!destination.isDirectory && !destination.mkdirs()) {
            throw IOException("Cannot create destination ${destination.absolutePath}")
        }
        val root = destination.canonicalFile

        var entries = 0
        var hardLinks = 0
        var symlinks = 0
        var pendingLongName: String? = null
        var pendingPax: Map<String, String> = emptyMap()

        GZIPInputStream(source, GZIP_BUFFER).use { gzip ->
            val header = ByteArray(TarHeader.BLOCK_SIZE)
            while (true) {
                if (!gzip.readFully(header)) break
                if (header.all { it == ZERO_BYTE }) break

                val entry = TarHeader.parse(header)

                // Metadata carriers describe the entry that follows and are
                // never files themselves. Writing them out is what produced a
                // `PaxHeaders` directory beside almost every real directory.
                when (entry.typeFlag) {
                    TarHeader.TYPE_PAX_EXTENDED -> {
                        pendingPax = parsePaxRecords(gzip.readEntry(entry.size))
                        continue
                    }

                    TarHeader.TYPE_PAX_GLOBAL -> {
                        gzip.skipEntry(entry.size)
                        continue
                    }

                    TarHeader.TYPE_GNU_LONG_NAME -> {
                        pendingLongName = gzip.readEntry(entry.size).decodeToString().trimEnd(NUL_CHAR)
                        continue
                    }
                }

                // pax wins over the 512-byte header: that is the point of it -
                // paths past 100 bytes and sizes past 8 GB only fit there.
                val name = pendingPax[PAX_PATH] ?: pendingLongName ?: entry.name
                val linkName = pendingPax[PAX_LINK_PATH] ?: entry.linkName
                val size = pendingPax[PAX_SIZE]?.toLongOrNull() ?: entry.size
                pendingLongName = null
                pendingPax = emptyMap()

                when (entry.typeFlag) {
                    TarHeader.TYPE_DIRECTORY -> {
                        resolveSafely(root, name).mkdirs()
                        gzip.skipEntry(size)
                    }

                    TarHeader.TYPE_SYMLINK -> {
                        writeSymlink(resolveSafely(root, name), linkName)
                        gzip.skipEntry(size)
                        symlinks++
                    }

                    TarHeader.TYPE_HARDLINK -> {
                        // Copy rather than link: link(2) is denied here.
                        copyHardLink(root, resolveSafely(root, name), linkName)
                        gzip.skipEntry(size)
                        hardLinks++
                    }

                    // Character/block devices and FIFOs cannot exist in app
                    // storage; skipping them is correct, not a failure.
                    TarHeader.TYPE_CHAR, TarHeader.TYPE_BLOCK, TarHeader.TYPE_FIFO ->
                        gzip.skipEntry(size)

                    else -> {
                        val target = resolveSafely(root, name)
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { output ->
                            gzip.copyEntry(size, output)
                        }
                        applyMode(target, entry.mode)
                        gzip.skipPadding(size)
                        entries++
                    }
                }
            }
        }
        return Result(entries, hardLinks, symlinks)
    }

    private fun writeSymlink(link: File, target: String) {
        link.parentFile?.mkdirs()
        if (link.exists()) link.delete()
        try {
            Files.createSymbolicLink(link.toPath(), File(target).toPath())
        } catch (cause: Exception) {
            // Some filesystems refuse symlinks; a copy of the target keeps the
            // rootfs working where the link would only have pointed.
            val resolved = File(link.parentFile, target)
            if (resolved.isFile) resolved.copyTo(link, overwrite = true)
        }
    }

    private fun copyHardLink(root: File, link: File, targetPath: String) {
        val target = File(root, targetPath)
        link.parentFile?.mkdirs()
        if (!target.isFile) return
        target.copyTo(link, overwrite = true)
        link.setExecutable(target.canExecute(), false)
    }

    /** Rejects `../` traversal ("tar slip") before anything is written. */
    private fun resolveSafely(destination: File, entryName: String): File {
        val resolved = File(destination, entryName).canonicalFile
        val inside = resolved.path.startsWith(destination.path + File.separator)
        if (!inside && resolved != destination) {
            throw IOException("Archive entry escapes destination: $entryName")
        }
        return resolved
    }

    private fun applyMode(file: File, mode: Int) {
        file.setReadable(mode and MODE_READ != 0, false)
        file.setWritable(mode and MODE_WRITE != 0, true)
        file.setExecutable(mode and MODE_EXECUTE != 0, false)
    }

    private companion object {
        const val ZERO_BYTE: Byte = 0
        val NUL_CHAR = Char(0)
        const val GZIP_BUFFER = 64 * 1024

        const val PAX_PATH = "path"
        const val PAX_LINK_PATH = "linkpath"
        const val PAX_SIZE = "size"

        const val MODE_READ = 0b100_000_000
        const val MODE_WRITE = 0b010_000_000
        const val MODE_EXECUTE = 0b001_000_000
    }
}
