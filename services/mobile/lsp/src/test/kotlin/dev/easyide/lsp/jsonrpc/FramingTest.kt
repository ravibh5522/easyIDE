package dev.easyide.lsp.jsonrpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import kotlin.random.Random

class FramingTest {

    private fun frame(body: String, headers: String = ""): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return "${headers}Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII) + bytes
    }

    /** Delivers [data] in the given chunk sizes, one `read` per chunk at most. */
    private class ChunkedInput(private val data: ByteArray, private val sizes: Iterator<Int>) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, data.size - pos, if (sizes.hasNext()) sizes.next().coerceAtLeast(1) else len)
            data.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }
    }

    private fun readAll(input: InputStream): List<JsonElement> {
        val reader = FrameReader(input)
        return generateSequence { reader.read()?.json }.toList()
    }

    private val messages = listOf(
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
        """{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"text":"héllo wörld ✓ 𝄞 日本語"}}""",
        """{"jsonrpc":"2.0","id":"abc","result":null}""",
        """{"jsonrpc":"2.0","id":2,"error":{"code":-32601,"message":"nope"}}""",
    )

    @Test
    fun severalFramesInOneChunkDecodeInOrder() {
        val all = messages.map { frame(it) }.reduce(ByteArray::plus)
        val decoded = readAll(ByteArrayInputStream(all))
        assertEquals(messages.size, decoded.size)
        assertEquals("initialize", decoded[0].jsonObject["method"]!!.jsonPrimitive.content)
    }

    @Test
    fun everySplitPointOfTheStreamDecodesIdentically() {
        val all = messages.map { frame(it) }.reduce(ByteArray::plus)
        val expected = readAll(ByteArrayInputStream(all))
        for (split in 1 until all.size) {
            val sizes = listOf(split, all.size).iterator()
            assertEquals("split at $split", expected, readAll(ChunkedInput(all, sizes)))
        }
    }

    @Test
    fun randomChunkSizesDecodeIdentically() {
        val all = messages.map { frame(it) }.reduce(ByteArray::plus)
        val expected = readAll(ByteArrayInputStream(all))
        val random = Random(SEED)
        repeat(RANDOM_ROUNDS) {
            val sizes = generateSequence { random.nextInt(1, 7) }.iterator()
            assertEquals(expected, readAll(ChunkedInput(all, sizes)))
        }
    }

    @Test
    fun byteLengthNotCharLengthIsUsedForMultiByteBodies() {
        val body = """{"jsonrpc":"2.0","method":"m","params":{"t":"𝄞é"}}"""
        assertTrue(body.toByteArray().size > body.length)
        val decoded = readAll(ChunkedInput(frame(body), generateSequence { 1 }.iterator()))
        assertEquals("𝄞é", decoded.single().jsonObject["params"]!!.jsonObject["t"]!!.jsonPrimitive.content)
    }

    @Test
    fun headerNamesAreCaseInsensitiveAndOtherHeadersIgnored() {
        val body = """{"jsonrpc":"2.0","method":"m"}"""
        val bytes = "content-type: application/vscode-jsonrpc; charset=utf-8\r\nCONTENT-LENGTH:   ${body.length}  \r\nX-Other: 1\r\n\r\n$body".toByteArray()
        assertEquals(1, readAll(ByteArrayInputStream(bytes)).size)
    }

    @Test
    fun cleanEofBetweenFramesIsNull() {
        assertNull(FrameReader(ByteArrayInputStream(ByteArray(0))).read())
    }

    @Test
    fun eofInsideHeaderOrBodyIsTransportEnd() {
        expect<EOFException> { FrameReader(ByteArrayInputStream("Content-Len".toByteArray())).read() }
        val truncated = frame("""{"jsonrpc":"2.0","method":"m"}""").dropLast(3).toByteArray()
        expect<EOFException> { FrameReader(ByteArrayInputStream(truncated)).read() }
    }

    @Test
    fun malformedHeadersAreProtocolErrors() {
        val cases = listOf(
            "\r\n{}",                                   // no Content-Length
            "Content-Length: abc\r\n\r\n{}",
            "Content-Length: -5\r\n\r\n{}",
            "Content-Length: 0\r\n\r\n",
            "Content-Length: 2\nX: y\r\n\r\n{}",       // bare LF
            "garbage line without colon\r\n\r\n{}",
            "Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}",
            "hello from print()\r\n\r\n",
        )
        for (c in cases) expect<ProtocolException>(c) { FrameReader(ByteArrayInputStream(c.toByteArray())).read() }
    }

    @Test
    fun oversizeFrameAndHeaderAreRefusedBeforeReadingTheBody() {
        expect<ProtocolException> { FrameReader(ByteArrayInputStream("Content-Length: 101\r\n\r\n".toByteArray()), maxMessageBytes = 100).read() }
        val longHeader = "X-Long: ${"a".repeat(200)}\r\nContent-Length: 2\r\n\r\n{}"
        expect<ProtocolException> { FrameReader(ByteArrayInputStream(longHeader.toByteArray()), maxHeaderBytes = 64).read() }
    }

    @Test
    fun jsonDeeperThanTheLimitIsRefusedButStringsDoNotCount() {
        val deep = "[".repeat(20) + "]".repeat(20)
        expect<ProtocolException> { FrameReader(ByteArrayInputStream(frame(deep)), maxJsonDepth = 10).read() }
        val shallowWithBrackets = """{"s":"[[[[[[[[[[[[[[[[[[[[\"]]]"}"""
        val ok = FrameReader(ByteArrayInputStream(frame(shallowWithBrackets)), maxJsonDepth = 10).read()
        assertEquals("[[[[[[[[[[[[[[[[[[[[\"]]]", ok!!.json.jsonObject["s"]!!.jsonPrimitive.content)
    }

    @Test
    fun malformedJsonIsProtocolError() {
        expect<ProtocolException> { FrameReader(ByteArrayInputStream(frame("{not json"))).read() }
    }

    @Test
    fun writerProducesOneFrameTheReaderAccepts() {
        val out = ByteArrayOutputStream()
        val msg = buildJsonObject { put("jsonrpc", JsonPrimitive("2.0")); put("method", JsonPrimitive("ü")) }
        val size = FrameWriter(out).write(msg)
        val bytes = out.toByteArray()
        assertTrue(String(bytes, Charsets.UTF_8).startsWith("Content-Length: $size\r\n\r\n"))
        assertEquals(msg, FrameReader(ByteArrayInputStream(bytes)).read()!!.json)
    }

    private inline fun <reified T : Throwable> expect(label: String = "", block: () -> Unit) {
        try {
            block()
            fail("expected ${T::class.simpleName} for '$label'")
        } catch (e: Throwable) {
            if (e !is T) throw AssertionError("expected ${T::class.simpleName} for '$label', got $e", e)
        }
    }

    private companion object {
        const val SEED = 42
        const val RANDOM_ROUNDS = 200
    }
}
