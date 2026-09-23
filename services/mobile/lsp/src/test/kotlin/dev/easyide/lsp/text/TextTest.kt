package dev.easyide.lsp.text

import dev.easyide.lsp.protocol.Position
import dev.easyide.lsp.protocol.Range
import dev.easyide.lsp.protocol.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TextTest {

    @Test
    fun lineIndexHandlesAllThreeTerminators() {
        val idx = LineIndex("a\nbb\r\nccc\rd")
        assertEquals(4, idx.lineCount)
        assertEquals(Position(1, 0), idx.position(2))
        assertEquals(Position(2, 1), idx.position(7))
        assertEquals(Position(3, 0), idx.position(10))
        assertEquals(10, idx.offset(Position(3, 0)))
    }

    @Test
    fun offsetsClampToLineEndAndTextEnd() {
        val idx = LineIndex("ab\r\ncd")
        // Column past the end of line 0 stops before its CRLF.
        assertEquals(2, idx.offset(Position(0, 99)))
        assertEquals(6, idx.offset(Position(9, 0)))
        assertEquals(Position(1, 2), idx.position(99))
    }

    @Test
    fun columnsAreUtf16Units() {
        val text = "x𝄞y" // U+1D11E is a surrogate pair
        val idx = LineIndex(text)
        assertEquals(Position(0, 3), idx.position(text.indexOf('y')))
    }

    @Test
    fun diffCasesProduceOneCorrectRange() {
        val cases = listOf(
            "hello world" to "hello brave world",   // insert
            "hello brave world" to "hello world",   // delete
            "abc" to "axc",                         // replace
            "" to "new text",                       // from empty
            "old" to "",                            // to empty
            "line1\nline2\n" to "line1\nline2\nline3\n",
            "a\r\nb" to "a\r\nXb",
            "a\r\nb" to "a\rX\nb",                  // edit between CR and LF
            "x𝄞y" to "x𝄡y",                         // same high surrogate, different low
            "aaaa" to "aaa",                        // repeated chars
        )
        for ((old, new) in cases) {
            val change = TextDiff.compute(old, new)!!
            assertEquals("$old -> $new", new, applyChange(old, change))
            assertTrue("no cut CRLF: $old -> $new", !cutsPair(old, LineIndex(old).offset(change.range.start)))
        }
        assertNull(TextDiff.compute("same", "same"))
    }

    @Test
    fun deltaReportsLinesMoved() {
        val change = TextDiff.compute("a\nb\nc\n", "a\nX\nY\nb\nc\n")!!
        assertEquals(1, change.delta.startLine)
        assertEquals(2, change.delta.lineDelta)
    }

    @Test
    fun randomEditSequencesKeepAModelServerInSync() {
        val random = Random(SEED)
        val alphabet = "ab\n\r é𝄞\t"
        var client = "start\n"
        var server = client
        repeat(ROUNDS) {
            val next = mutate(client, random, alphabet)
            TextDiff.compute(client, next)?.let { server = applyChange(server, it) }
            client = next
            assertEquals(client, server)
        }
    }

    @Test
    fun flushOf400KbDocumentIsFast() {
        val base = buildString { repeat(LINES) { append("def f$it(x):\n    return x * $it\n") } }
        val edited = base.substring(0, base.length / 2) + "#" + base.substring(base.length / 2)
        val oldIndex = LineIndex(base)
        repeat(WARMUP) { TextDiff.compute(base, edited, oldIndex) }
        // Best of several runs: a shared, loaded build machine adds noise, never speed.
        val ms = (1..RUNS).minOf {
            val start = System.nanoTime()
            TextDiff.compute(base, edited, oldIndex)
            (System.nanoTime() - start) / NANOS_PER_MS
        }
        // Budget is < 2 ms on the device (R-PERF-07); a JVM CI box gets headroom, not a pass by default.
        assertTrue("diff took $ms ms", ms < JVM_BUDGET_MS)
        assertTrue(base.length > MIN_SIZE)
    }

    @Test
    fun textEditsApplyAgainstOriginalOffsetsAndKeepInsertOrder() {
        val text = "one two three"
        val edits = listOf(
            TextEdit(Range(Position(0, 8), Position(0, 13)), "3"),
            TextEdit(Range(Position(0, 0), Position(0, 3)), "1"),
            TextEdit(Range(Position(0, 4), Position(0, 4)), "A"),
            TextEdit(Range(Position(0, 4), Position(0, 4)), "B"),
        )
        assertEquals("1 ABtwo 3", TextEdits.apply(text, edits))
        val overlapping = listOf(TextEdit(Range(Position(0, 0), Position(0, 5)), ""), TextEdit(Range(Position(0, 2), Position(0, 6)), ""))
        assertNull(TextEdits.apply(text, overlapping))
    }

    private fun applyChange(text: String, change: TextChange): String {
        val idx = LineIndex(text)
        val s = idx.offset(change.range.start)
        val e = idx.offset(change.range.end)
        return text.substring(0, s) + change.text + text.substring(e)
    }

    private fun cutsPair(text: String, offset: Int): Boolean =
        offset in 1 until text.length && ((text[offset - 1] == '\r' && text[offset] == '\n') || text[offset].isLowSurrogate())

    private fun mutate(text: String, random: Random, alphabet: String): String {
        val a = random.nextInt(text.length + 1)
        val b = (a + random.nextInt(minOf(4, text.length - a) + 1)).coerceAtMost(text.length)
        val insert = buildString { repeat(random.nextInt(4)) { append(alphabet[random.nextInt(alphabet.length)]) } }
        return text.substring(0, a) + insert + text.substring(b)
    }

    private companion object {
        const val SEED = 7
        const val ROUNDS = 2000
        const val LINES = 12_000
        const val WARMUP = 20
        const val RUNS = 10
        const val NANOS_PER_MS = 1_000_000L
        const val JVM_BUDGET_MS = 20L
        const val MIN_SIZE = 400_000
    }
}
