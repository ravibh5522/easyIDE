package dev.easyide.app.ui.screens.workspace.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EditHistoryTest {

    private val limits = HistoryLimits(coalesceIdleMs = 1_000)

    /** Types [chars] one at a time at the caret, [gap] ms apart, from [state]; returns the final state. */
    private fun EditHistory.type(state: TextState, chars: String, startMs: Long, gap: Long = 100): TextState {
        var current = state
        var now = startMs
        for (c in chars) {
            val caret = current.selectionEnd
            val text = current.text.substring(0, caret) + c + current.text.substring(caret)
            val next = TextState(text, caret + 1)
            record(current, next, now)
            current = next
            now += gap
        }
        return current
    }

    private fun EditHistory.undoAll(from: String): String {
        var text = from
        while (canUndo) text = undo(text)!!.text
        return text
    }

    @Test
    fun `a typed run is one step split where a new word starts`() {
        val h = EditHistory("", limits)
        val end = h.type(TextState("", 0), "hello world", 0)
        assertEquals("hello world", end.text)
        val first = h.undo(end.text)!!
        assertEquals("hello ", first.text)
        assertEquals(6, first.selectionEnd)
        val second = h.undo(first.text)!!
        assertEquals("", second.text)
        assertFalse(h.canUndo)
    }

    @Test
    fun `a pause longer than the idle limit starts a new step`() {
        val h = EditHistory("", limits)
        var s = h.type(TextState("", 0), "ab", 0)
        s = h.type(s, "cd", 5_000)
        assertEquals("ab", h.undo(s.text)!!.text)
        assertEquals("", h.undo("ab")!!.text)
    }

    @Test
    fun `moving the caret between keystrokes breaks the run`() {
        val h = EditHistory("", limits)
        val a = h.type(TextState("", 0), "ab", 0)
        // The user tapped elsewhere: same text, different caret, then typed.
        val moved = a.copy(selectionStart = 0, selectionEnd = 0)
        val b = h.type(moved, "x", 300)
        assertEquals("xab", b.text)
        assertEquals("ab", h.undo(b.text)!!.text)
        assertEquals("", h.undo("ab")!!.text)
    }

    @Test
    fun `undo restores the selection from before the step and redo the one after`() {
        val h = EditHistory("abc def", limits)
        val before = TextState("abc def", 4, 7)
        val after = TextState("abc x", 5)
        h.record(before, after, 0)
        val undone = h.undo("abc x")!!
        assertEquals(before, undone)
        val redone = h.redo(undone.text)!!
        assertEquals(after, redone)
    }

    @Test
    fun `backspacing a run merges and restores every character`() {
        val h = EditHistory("hello", limits)
        var s = TextState("hello", 5)
        var now = 0L
        repeat(3) {
            val next = TextState(s.text.dropLast(1), s.selectionEnd - 1)
            h.record(s, next, now)
            s = next
            now += 100
        }
        assertEquals("he", s.text)
        assertEquals("hello", h.undo(s.text)!!.text)
        assertFalse(h.canUndo)
    }

    @Test
    fun `forward deletes merge into one step`() {
        val h = EditHistory("hello", limits)
        var s = TextState("hello", 0)
        repeat(2) {
            val next = TextState(s.text.substring(1), 0)
            h.record(s, next, it * 100L)
            s = next
        }
        assertEquals("llo", s.text)
        assertEquals("hello", h.undo(s.text)!!.text)
    }

    @Test
    fun `a newline is its own step and typing after it starts another`() {
        val h = EditHistory("", limits)
        var s = h.type(TextState("", 0), "ab", 0)
        val enter = TextState("ab\n    ", 7) // auto-indent added the spaces in the same edit
        h.record(s, enter, 300)
        s = h.type(enter, "c", 400)
        assertEquals("ab\n    c", s.text)
        assertEquals("ab\n    ", h.undo(s.text)!!.text)
        assertEquals("ab", h.undo("ab\n    ")!!.text)
        assertEquals("", h.undo("ab")!!.text)
    }

    @Test
    fun `auto-closed brackets are one step`() {
        val h = EditHistory("a", limits)
        val typed = TextState("a()", 2)
        h.record(TextState("a", 1), typed, 0)
        assertEquals("a", h.undo("a()")!!.text)
        assertFalse(h.canUndo)
    }

    @Test
    fun `a programmatic multi-character edit is one step even next to typing`() {
        val h = EditHistory("x", limits)
        h.record(TextState("x", 1), TextState("x", 1).let { TextState("x = 1\n", 6) }, 0)
        val s = h.type(TextState("x = 1\n", 6), "y", 100)
        assertEquals("x = 1\ny", s.text)
        assertEquals("x = 1\n", h.undo(s.text)!!.text)
        assertEquals("x", h.undo("x = 1\n")!!.text)
    }

    @Test
    fun `a new edit clears redo`() {
        val h = EditHistory("", limits)
        val s = h.type(TextState("", 0), "ab", 0)
        h.undo(s.text)
        assertTrue(h.canRedo)
        h.type(TextState("", 0), "z", 9_000)
        assertFalse(h.canRedo)
        assertNull(h.redo("z"))
    }

    @Test
    fun `typing after an undo does not merge into the step before it`() {
        val h = EditHistory("", limits)
        var s = h.type(TextState("", 0), "a", 0)
        s = h.type(s, "b", 5_000)
        val undone = h.undo(s.text)!!
        val next = h.type(undone, "c", 5_100)
        assertEquals("ac", next.text)
        assertEquals("a", h.undo(next.text)!!.text)
    }

    @Test
    fun `a buffer changed behind the history's back restarts it`() {
        val h = EditHistory("a", limits)
        h.type(TextState("a", 1), "b", 0)
        // The file was reloaded: the next edit starts from text the history never saw.
        h.record(TextState("reloaded", 8), TextState("reloaded!", 9), 100)
        assertEquals("reloaded", h.undo("reloaded!")!!.text)
        assertFalse(h.canUndo)
    }

    @Test
    fun `undo with a stale buffer drops the history and does nothing`() {
        val h = EditHistory("", limits)
        h.type(TextState("", 0), "abc", 0)
        assertNull(h.undo("something else"))
        assertFalse(h.canUndo)
    }

    @Test
    fun `equal texts record nothing`() {
        val h = EditHistory("abc", limits)
        assertFalse(h.record(TextState("abc", 0), TextState("abc", 3), 0))
        assertFalse(h.canUndo)
    }

    @Test
    fun `the step count is capped and the oldest goes first`() {
        val h = EditHistory("", HistoryLimits(coalesceIdleMs = 0, maxSteps = 3))
        var s = TextState("", 0)
        var now = 0L
        repeat(10) {
            s = h.type(s, "x", now)
            now += 10_000
        }
        var steps = 0
        var text = s.text
        while (h.canUndo) { text = h.undo(text)!!.text; steps++ }
        assertEquals(3, steps)
        assertEquals("x".repeat(7), text)
    }

    @Test
    fun `retained characters are capped but the newest step is always kept`() {
        val h = EditHistory("", HistoryLimits(maxRetainedChars = 100))
        val big = "y".repeat(500)
        h.record(TextState("", 0), TextState(big, 500), 0)
        assertTrue(h.canUndo)
        assertEquals("", h.undo(big)!!.text)
        h.record(TextState("", 0), TextState("a", 1), 10_000)
        h.record(TextState("a", 1), TextState("a$big", 501), 20_000)
        // The oldest step was dropped to make room; the newest stays.
        assertEquals("a", h.undo("a$big")!!.text)
        assertFalse(h.canUndo)
    }

    @Test
    fun `an emoji typed after a letter joins the run and undoes whole`() {
        val h = EditHistory("", limits)
        val s = h.type(TextState("", 0), "a", 0)
        val withEmoji = TextState("a😀", 3)
        h.record(s, withEmoji, 50)
        assertEquals("", h.undo(withEmoji.text)!!.text)
        assertFalse(h.canUndo)
    }

    @Test
    fun `random edit sequences undo to the baseline and redo to the end`() {
        repeat(200) { seed ->
            val random = Random(seed)
            val baseline = randomText(random, random.nextInt(0, 30))
            val h = EditHistory(baseline, limits)
            val states = mutableListOf(baseline)
            var s = TextState(baseline, random.nextInt(baseline.length + 1))
            var now = 0L
            repeat(random.nextInt(1, 60)) {
                now += listOf(10L, 50L, 400L, 3_000L).random(random)
                val next = randomEdit(random, s)
                if (next.text != s.text) states += next.text
                h.record(s, next, now)
                s = next
                assertEquals("head follows the buffer (seed $seed)", s.text, h.head)
            }
            val final = s.text
            val seen = states.toSet()
            var text = final
            while (h.canUndo) {
                text = h.undo(text)!!.text
                assertTrue("undo landed on a state that never existed (seed $seed)", text in seen)
            }
            assertEquals("seed $seed", baseline, text)
            while (h.canRedo) {
                text = h.redo(text)!!.text
                assertTrue("redo landed on a state that never existed (seed $seed)", text in seen)
            }
            assertEquals("seed $seed", final, text)
        }
    }

    @Test
    fun `random undo redo and edit interleavings keep head equal to the buffer`() {
        repeat(200) { seed ->
            val random = Random(1_000 + seed)
            val h = EditHistory("seed", limits)
            var s = TextState("seed", 4)
            var now = 0L
            repeat(80) {
                now += random.nextLong(0, 2_000)
                when (random.nextInt(5)) {
                    0 -> h.undo(s.text)?.let { s = it }
                    1 -> h.redo(s.text)?.let { s = it }
                    else -> {
                        val next = randomEdit(random, s)
                        h.record(s, next, now)
                        s = next
                    }
                }
                assertEquals("seed $seed", s.text, h.head)
            }
            // Whatever happened, undoing everything ends at a state whose redo returns here.
            val end = s.text
            var t = end
            var undone = 0
            while (h.canUndo) { t = h.undo(t)!!.text; undone++ }
            repeat(undone) { t = assertNotNull(h.redo(t)).text }
            assertEquals("seed $seed", end, t)
        }
    }

    private fun assertNotNull(state: TextState?): TextState {
        org.junit.Assert.assertNotNull(state)
        return state!!
    }

    private fun randomText(random: Random, length: Int): String =
        String(CharArray(length) { "ab \n(x_".random(random) })

    /** A typed char, a backspace, a forward delete, a paste, a selection replace, or a caret move. */
    private fun randomEdit(random: Random, s: TextState): TextState {
        val t = s.text
        val caret = s.selectionEnd.coerceIn(0, t.length)
        return when (random.nextInt(7)) {
            0, 1 -> insert(t, caret, randomText(random, 1))
            2 -> if (caret > 0) TextState(t.removeRange(caret - 1, caret), caret - 1) else s
            3 -> if (caret < t.length) TextState(t.removeRange(caret, caret + 1), caret) else s
            4 -> insert(t, caret, randomText(random, random.nextInt(2, 8)))
            5 -> {
                val a = random.nextInt(t.length + 1)
                val b = random.nextInt(t.length + 1)
                val lo = minOf(a, b)
                val hi = maxOf(a, b)
                val ins = randomText(random, random.nextInt(0, 3))
                TextState(t.replaceRange(lo, hi, ins), lo + ins.length)
            }
            else -> TextState(t, random.nextInt(t.length + 1))
        }
    }

    private fun insert(text: String, at: Int, s: String) = TextState(text.substring(0, at) + s + text.substring(at), at + s.length)
}
