package dev.easyide.app.ui.props

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlinkClockTest {
    private val half = Motion.BLINK_HALF_MS.toLong()
    private val period = BlinkClock.PERIOD_MS

    private fun on(now: Long, start: Long = 0, input: Long? = null, cap: Int? = null) = BlinkClock.visible(now, start, input, cap)
    private fun next(now: Long, start: Long = 0, input: Long? = null, cap: Int? = null) = BlinkClock.msUntilChange(now, start, input, cap)

    @Test fun `a period is 530ms on then 530ms off with no fade`() {
        assertEquals(1060L, period)
        assertTrue(on(0)); assertTrue(on(half - 1))
        assertFalse(on(half)); assertFalse(on(period - 1))
        assertTrue(on(period))
    }

    @Test fun `the next change is the end of the current half`() {
        assertEquals(half, next(0))
        assertEquals(1L, next(half - 1))
        assertEquals(half, next(half))
        assertEquals(1L, next(period - 1))
    }

    @Test fun `input holds the block solid for 600ms and the blink then restarts from on`() {
        val input = 700L // would be mid off-phase without the hold
        assertTrue(on(input, input = input))
        assertTrue(on(input + Motion.BLINK_RESET_MS - 1, input = input))
        assertTrue(on(input + Motion.BLINK_RESET_MS, input = input))
        assertFalse(on(input + Motion.BLINK_RESET_MS + half, input = input))
        assertEquals(Motion.BLINK_RESET_MS.toLong(), next(input, input = input))
    }

    @Test fun `input before the start does not delay the blink`() {
        assertEquals(half, next(1000, start = 1000, input = 0))
    }

    @Test fun `a capped cursor blinks its cycles then stays solid and asks for no more wake-ups`() {
        val cap = 6
        val end = cap * period
        assertFalse(on(end - half, cap = cap))
        assertTrue(on(end, cap = cap))
        assertTrue(on(end + half, cap = cap))
        assertNull(next(end, cap = cap))
        assertNull(next(end + 10 * period, cap = cap))
    }

    @Test fun `the wake-up before the cap lands on the cap even in an off phase`() {
        val cap = 6
        val end = cap * period
        assertEquals(half, next(end - period, cap = cap))
        assertEquals(1L, next(end - 1, cap = cap))
    }

    @Test fun `zero cycles is solid at once`() {
        assertTrue(on(half + 1, cap = 0))
        assertNull(next(0, cap = 0))
    }

    @Test fun `six cycles is the header cap`() {
        assertEquals(6, Motion.HEADER_BLINK_CYCLES)
    }

    @Test fun `reduce motion and screen reader keep the cursor solid`() {
        val reduced = Motion.of(Appearance(reduceMotion = ReduceMotion.ON), systemReduces = false)
        assertFalse(reduced.cursorBlinks)
        assertFalse(Motion.of(Appearance(), systemReduces = true).cursorBlinks)
        assertFalse(Motion.of(Appearance(), systemReduces = false, screenReaderOn = true).cursorBlinks)
        assertTrue(Motion.of(Appearance(), systemReduces = false).cursorBlinks)
    }
}
