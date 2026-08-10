package dev.tabcode.sandbox.bootstrap

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One parsed 512-byte tar header block. */
internal data class TarHeader(
    val name: String,
    val size: Long,
    val mode: Int,
    val typeFlag: Char,
    val linkName: String,
) {
    companion object {
        const val BLOCK_SIZE = 512

        const val TYPE_HARDLINK = '1'
        const val TYPE_SYMLINK = '2'
        const val TYPE_CHAR = '3'
        const val TYPE_BLOCK = '4'
        const val TYPE_DIRECTORY = '5'
        const val TYPE_FIFO = '6'
        const val TYPE_GNU_LONG_NAME = 'L'

        /** pax extended header: metadata for the entry that follows it. */
        const val TYPE_PAX_EXTENDED = 'x'

        /** pax global header: archive-wide defaults. */
        const val TYPE_PAX_GLOBAL = 'g'

        private const val OFFSET_NAME = 0
        private const val LENGTH_NAME = 100
        private const val OFFSET_MODE = 100
        private const val LENGTH_MODE = 8
        private const val OFFSET_SIZE = 124
        private const val LENGTH_SIZE = 12
        private const val OFFSET_TYPE = 156
        private const val OFFSET_LINK_NAME = 157
        private const val LENGTH_LINK_NAME = 100
        private const val OFFSET_PREFIX = 345
        private const val LENGTH_PREFIX = 155

        private const val OCTAL_RADIX = 8

        fun parse(block: ByteArray): TarHeader {
            val name = block.readString(OFFSET_NAME, LENGTH_NAME)
            val prefix = block.readString(OFFSET_PREFIX, LENGTH_PREFIX)
            return TarHeader(
                name = if (prefix.isEmpty()) name else "$prefix/$name",
                size = block.readOctal(OFFSET_SIZE, LENGTH_SIZE),
                mode = block.readOctal(OFFSET_MODE, LENGTH_MODE).toInt(),
                typeFlag = block[OFFSET_TYPE].toInt().toChar(),
                linkName = block.readString(OFFSET_LINK_NAME, LENGTH_LINK_NAME),
            )
        }

        private fun ByteArray.readString(offset: Int, length: Int): String {
            val end = (offset until offset + length).firstOrNull { this[it] == ZERO } ?: (offset + length)
            return String(this, offset, end - offset, Charsets.UTF_8)
        }

        private fun ByteArray.readOctal(offset: Int, length: Int): Long {
            val text = readString(offset, length).trim()
            if (text.isEmpty()) return 0
            return text.toLongOrNull(OCTAL_RADIX)
                ?: throw IOException("Malformed tar numeric field: '$text'")
        }

        private const val ZERO: Byte = 0
    }
}

/**
 * Parses a pax extended header payload into its records.
 *
 * The format is a run of `"<len> <key>=<value>\n"`, where `<len>` counts the
 * whole record including the digits and the newline. Parsed over bytes rather
 * than a decoded string because that length is in bytes, and a non-ASCII value
 * would desync a character-indexed walk.
 *
 * Keys this extractor does not act on (timestamps, uid/gid names) are parsed
 * and ignored - the point is to *consume* the header so it is never mistaken
 * for a file, which is what littered every rootfs with `PaxHeaders` directories
 * and made dpkg refuse to read `/etc/dpkg/dpkg.cfg.d`.
 */
internal fun parsePaxRecords(payload: ByteArray): Map<String, String> {
    val records = mutableMapOf<String, String>()
    var offset = 0
    while (offset < payload.size) {
        val space = payload.indexOfByte(SPACE_BYTE, offset)
        if (space < 0) break

        val length = String(payload, offset, space - offset, Charsets.US_ASCII).toIntOrNull() ?: break
        val end = offset + length
        val bodyLength = end - space - 2
        if (length <= 0 || end > payload.size || bodyLength < 0) break

        val body = String(payload, space + 1, bodyLength, Charsets.UTF_8)
        val separator = body.indexOf('=')
        if (separator > 0) records[body.substring(0, separator)] = body.substring(separator + 1)
        offset = end
    }
    return records
}

private fun ByteArray.indexOfByte(value: Byte, from: Int): Int {
    for (index in from until size) if (this[index] == value) return index
    return -1
}

/** Reads exactly [buffer].size bytes, or returns false at a clean end of stream. */
internal fun InputStream.readFully(buffer: ByteArray): Boolean {
    var offset = 0
    while (offset < buffer.size) {
        val read = read(buffer, offset, buffer.size - offset)
        if (read < 0) {
            // End of stream on a block boundary is the normal archive end;
            // mid-block it means the archive was cut short.
            if (offset == 0) return false
            throw IOException("Truncated tar archive")
        }
        offset += read
    }
    return true
}

/** Copies [size] entry bytes to [output], then consumes the block padding. */
internal fun InputStream.copyEntry(size: Long, output: OutputStream) {
    val buffer = ByteArray(COPY_BUFFER_SIZE)
    var remaining = size
    while (remaining > 0) {
        val toRead = minOf(remaining, buffer.size.toLong()).toInt()
        val read = read(buffer, 0, toRead)
        if (read < 0) throw IOException("Truncated tar entry")
        output.write(buffer, 0, read)
        remaining -= read
    }
}

/** Reads a whole small entry (long-name headers) including its padding. */
internal fun InputStream.readEntry(size: Long): ByteArray {
    val bytes = ByteArray(size.toInt())
    if (!readFully(bytes)) throw IOException("Truncated tar entry")
    skipPadding(size)
    return bytes
}

/** Consumes an entry's payload and its padding without materializing it. */
internal fun InputStream.skipEntry(size: Long) {
    var remaining = size
    val buffer = ByteArray(COPY_BUFFER_SIZE)
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
        if (read < 0) break
        remaining -= read
    }
    skipPadding(size)
}

/** tar pads every entry up to the next 512-byte boundary. */
internal fun InputStream.skipPadding(size: Long) {
    val remainder = (size % TarHeader.BLOCK_SIZE).toInt()
    if (remainder == 0) return
    val padding = ByteArray(TarHeader.BLOCK_SIZE - remainder)
    readFully(padding)
}

private const val COPY_BUFFER_SIZE = 8 * 1024
private const val SPACE_BYTE: Byte = 0x20
