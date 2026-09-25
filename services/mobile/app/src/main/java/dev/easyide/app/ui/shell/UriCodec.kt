package dev.easyide.app.ui.shell

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Percent-encoding for [DocumentUri]. Components are stored decoded and re-encoded the same way
 * every time, which is what makes two spellings of one location compare equal.
 */
internal object UriCodec {
    private const val HEX = "0123456789ABCDEF"
    private const val SUB_DELIMS = "!$&'()*+,;="
    private const val UNRESERVED_MARKS = "-._~"

    private fun unreserved(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in UNRESERVED_MARKS

    val segment: (Char) -> Boolean = { unreserved(it) || it in SUB_DELIMS || it == ':' || it == '@' }

    /** `&`, `=` and `+` separate or stand for something inside a query, so they are always escaped there. */
    val queryPart: (Char) -> Boolean = { unreserved(it) || it in "!$'()*,;:@/?" }

    val fragment: (Char) -> Boolean = { segment(it) || it == '/' || it == '?' }

    fun encode(text: String, keep: (Char) -> Boolean): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            if (cp < 0x80 && keep(cp.toChar())) {
                out.append(cp.toChar())
            } else {
                for (b in text.substring(i, i + len).toByteArray(Charsets.UTF_8)) {
                    out.append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
                }
            }
            i += len
        }
        return out.toString()
    }

    /** The decoded text, or null for a truncated or non-hex escape or bytes that are not UTF-8. */
    fun decode(text: String): String? {
        val bytes = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '%') {
                val hi = hexValue(text.getOrNull(i + 1)) ?: return null
                val lo = hexValue(text.getOrNull(i + 2)) ?: return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                val cp = text.codePointAt(i)
                bytes.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
                i += Character.charCount(cp)
            }
        }
        val input = ByteBuffer.wrap(bytes.toByteArray())
        val output = CharBuffer.allocate(input.remaining())
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = decoder.decode(input, output, true)
        if (result.isError || decoder.flush(output).isError) return null
        return output.flip().toString()
    }

    private fun hexValue(c: Char?): Int? = c?.let { Character.digit(it, 16) }?.takeIf { it >= 0 }
}
