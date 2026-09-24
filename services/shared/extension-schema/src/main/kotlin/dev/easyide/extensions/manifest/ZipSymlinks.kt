package dev.easyide.extensions.manifest

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** A zip whose directory cannot be trusted; the whole archive is refused. */
class ZipFormatException(message: String) : IOException(message)

/**
 * Finds symlink entries by reading the zip central directory, which `java.util.zip`
 * does not expose: an entry made on Unix (`version made by` host 3) whose external
 * attributes carry `S_IFLNK`. Unpacking would turn such an entry into a regular file,
 * but a package that contains one breaks the layout rules, so it is refused (EXT-02).
 * Shared by the app installer and `easyide-ext validate` so both audit archives the same way.
 */
object ZipSymlinks {
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

    /** Names of symlink entries; empty for a zip without any. @throws ZipFormatException on a malformed directory. */
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
            if (p + CENTRAL_FIXED + nameLen > cd.size) throw ZipFormatException("truncated zip directory")
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
        if (len < EOCD_MIN) throw ZipFormatException("not a zip archive")
        val tailLen = minOf(len, (EOCD_MIN + MAX_COMMENT).toLong()).toInt()
        val tail = ByteArray(tailLen)
        raf.seek(len - tailLen)
        raf.readFully(tail)
        var eocd = -1
        for (i in tailLen - EOCD_MIN downTo 0) if (int32(tail, i) == EOCD_SIG) { eocd = i; break }
        if (eocd < 0) throw ZipFormatException("not a zip archive")
        val size = int32(tail, eocd + 12).toLong() and U32
        val offset = int32(tail, eocd + 16).toLong() and U32
        if (size != U32 && offset != U32) return checked(offset, size, len)
        // Zip64: the locator sits just before the classic record.
        val locator = eocd - 20
        if (locator < 0 || int32(tail, locator) != EOCD64_LOCATOR_SIG) throw ZipFormatException("malformed zip64 archive")
        val record = int64(tail, locator + 8)
        val rec = ByteArray(56)
        raf.seek(record)
        raf.readFully(rec)
        return checked(int64(rec, 48), int64(rec, 40), len)
    }

    private fun checked(offset: Long, size: Long, fileLen: Long): Pair<Long, Long> {
        if (offset < 0 || size < 0 || offset + size > fileLen || size > Int.MAX_VALUE) throw ZipFormatException("malformed zip directory")
        return offset to size
    }

    private fun int16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) and U16
    private fun int32(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
        ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
    private fun int64(b: ByteArray, i: Int) = (int32(b, i).toLong() and U32) or ((int32(b, i + 4).toLong() and U32) shl 32)
}
