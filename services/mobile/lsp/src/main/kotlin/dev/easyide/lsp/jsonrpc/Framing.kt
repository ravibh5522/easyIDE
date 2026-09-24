package dev.easyide.lsp.jsonrpc

import dev.easyide.lsp.LspPolicy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** One decoded frame; [bytes] is the body size, kept for tracing. */
class Frame(val json: JsonElement, val bytes: Int)

/**
 * Pull-based reader of LSP base-protocol frames: ASCII headers terminated by `\r\n`, an empty
 * line, then exactly `Content-Length` bytes of UTF-8 JSON.
 *
 * The length is in bytes, so the body is decoded only after it has been read in full - a
 * multi-byte character straddling two pipe reads is never split. Partial reads and several
 * frames arriving in one chunk are both handled by reading byte-exact from a buffered stream.
 *
 * Not thread-safe: exactly one reader coroutine owns it.
 */
class FrameReader(
    input: InputStream,
    private val maxHeaderBytes: Int = LspPolicy.MAX_HEADER_BYTES,
    private val maxMessageBytes: Int = LspPolicy.MAX_MESSAGE_BYTES,
    private val maxJsonDepth: Int = LspPolicy.MAX_JSON_DEPTH,
) {
    private val input: InputStream = input as? BufferedInputStream ?: BufferedInputStream(input)
    private val header = ByteArray(maxHeaderBytes)

    /**
     * Reads the next frame and parses its JSON.
     *
     * @return the parsed frame, or null on a clean end of stream between frames.
     * @throws ProtocolException on a bad header, oversize frame, too-deep or malformed JSON.
     * @throws EOFException when the stream ends inside a frame.
     */
    fun read(): Frame? {
        val body = readBody() ?: return null
        checkDepth(body)
        val text = String(body, Charsets.UTF_8)
        val json = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw ProtocolException("malformed JSON: ${e.message}")
        }
        return Frame(json, body.size)
    }

    /** Raw body bytes of the next frame, or null on a clean EOF before any header byte. */
    private fun readBody(): ByteArray? {
        var contentLength = -1
        var used = 0
        var lineStart = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (used == 0) return null
                throw EOFException("stream ended inside a header")
            }
            if (used == maxHeaderBytes) throw ProtocolException("header section over $maxHeaderBytes bytes")
            header[used++] = b.toByte()
            if (b != LF) continue
            if (used - lineStart < 2 || header[used - 2] != CR) throw ProtocolException("header line not terminated by CRLF")
            val lineLength = used - 2 - lineStart
            if (lineLength == 0) break
            val line = String(header, lineStart, lineLength, Charsets.US_ASCII)
            val parsed = parseContentLength(line)
            if (parsed != null) {
                if (contentLength >= 0) throw ProtocolException("duplicate Content-Length")
                contentLength = parsed
            }
            lineStart = used
        }
        if (contentLength < 0) throw ProtocolException("missing Content-Length")
        return readExactly(contentLength)
    }

    /** Value of a `Content-Length` header line, null for any other (ignored) header. */
    private fun parseContentLength(line: String): Int? {
        val colon = line.indexOf(':')
        if (colon <= 0) throw ProtocolException("malformed header line")
        if (!line.substring(0, colon).trim().equals(CONTENT_LENGTH, ignoreCase = true)) return null
        val value = line.substring(colon + 1).trim()
        if (value.isEmpty() || value.length > MAX_LENGTH_DIGITS || !value.all { it in '0'..'9' }) {
            throw ProtocolException("Content-Length is not a decimal number")
        }
        val n = value.toLong()
        if (n <= 0 || n > maxMessageBytes) throw ProtocolException("Content-Length $n outside 1..$maxMessageBytes")
        return n.toInt()
    }

    // InputStream.readNBytes needs Android API 33; this loop works on every level.
    private fun readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw EOFException("stream ended after $off of $n body bytes")
            off += r
        }
        return out
    }

    /**
     * Rejects nesting deeper than [maxJsonDepth] by scanning bytes. Safe on UTF-8 because
     * every byte of a multi-byte sequence is >= 0x80, so it never equals a bracket or quote.
     */
    private fun checkDepth(body: ByteArray) {
        var depth = 0
        var inString = false
        var escaped = false
        for (b in body) {
            val c = b.toInt()
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == BACKSLASH -> escaped = true
                    c == QUOTE -> inString = false
                }
                continue
            }
            when (c) {
                QUOTE -> inString = true
                OPEN_BRACE, OPEN_BRACKET -> if (++depth > maxJsonDepth) {
                    throw ProtocolException("JSON nested deeper than $maxJsonDepth")
                }
                CLOSE_BRACE, CLOSE_BRACKET -> depth--
            }
        }
    }

    private companion object {
        const val CR: Byte = '\r'.code.toByte()
        const val LF = '\n'.code
        const val QUOTE = '"'.code
        const val BACKSLASH = '\\'.code
        const val OPEN_BRACE = '{'.code
        const val CLOSE_BRACE = '}'.code
        const val OPEN_BRACKET = '['.code
        const val CLOSE_BRACKET = ']'.code
        const val CONTENT_LENGTH = "Content-Length"

        /** More digits than this cannot be a legal length; stops Long overflow on garbage. */
        const val MAX_LENGTH_DIGITS = 12
    }
}

/**
 * Writes one frame per call: header and body in a single pre-sized array, one `write`, then
 * `flush`. Only the connection's single writer coroutine may call it (lsp-client.md sec 8).
 */
class FrameWriter(private val output: OutputStream) {

    /** @return the frame's body size in bytes, for tracing. */
    fun write(message: JsonElement): Int {
        val body = Json.encodeToString(JsonElement.serializer(), message).toByteArray(Charsets.UTF_8)
        val head = "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val frame = ByteArray(head.size + body.size)
        head.copyInto(frame)
        body.copyInto(frame, head.size)
        output.write(frame)
        output.flush()
        return body.size
    }
}
