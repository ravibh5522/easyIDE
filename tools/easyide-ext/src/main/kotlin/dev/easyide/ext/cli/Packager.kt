package dev.easyide.ext.cli

import dev.easyide.extensions.manifest.PackageFiles
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * cli.md sec 5.3: a deterministic zip. Entries sorted by UTF-8 bytes of the path, files only,
 * DOS-epoch timestamps, no extra fields or comments, DEFLATE at one fixed level except
 * already-compressed images (STORED). Same tree in, same bytes out.
 */
object Packager {
    private const val LEVEL = Deflater.BEST_COMPRESSION
    private val STORED_SUFFIXES = listOf(".png", ".webp")

    /**
     * `ZipEntry.setTime` encodes local wall-clock time into the DOS fields, so 1980-01-01 00:00
     * in the default zone is written as exactly that on every machine and time zone.
     */
    private val DOS_EPOCH: Long = LocalDateTime.of(1980, 1, 1, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun fileName(publisher: String, name: String, version: String) = "$publisher.$name-$version.easyext"

    fun zip(files: PackageFiles): ByteArray {
        val paths = files.list().sortedWith { a, b -> compareUtf8(a, b) }
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes, Charsets.UTF_8).use { zip ->
            zip.setLevel(LEVEL)
            zip.setMethod(ZipOutputStream.DEFLATED)
            for (path in paths) {
                val data = files.read(path)
                val entry = ZipEntry(path)
                entry.time = DOS_EPOCH
                if (STORED_SUFFIXES.any { path.lowercase().endsWith(it) }) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                } else {
                    entry.method = ZipEntry.DEFLATED
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun compareUtf8(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return x.size - y.size
    }
}
