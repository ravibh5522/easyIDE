package dev.easyide.app.ui.screens.workspace.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoToLineTest {

    @Test
    fun `parses a line and an optional column`() {
        assertEquals(GoToTarget(11, 0), GoToLine.parse("12", 100))
        assertEquals(GoToTarget(11, 4), GoToLine.parse(" 12:5 ", 100))
        assertEquals(GoToTarget(0, 2), GoToLine.parse("1,3", 100))
    }

    @Test
    fun `a line past the end lands on the last line`() {
        assertEquals(GoToTarget(9, 0), GoToLine.parse("500", 10))
        assertEquals(GoToTarget(0, 0), GoToLine.parse("3", 0))
    }

    @Test
    fun `anything that is not a positive number is rejected`() {
        listOf("", "  ", "abc", "0", "-3", "1:0", "1:x", "1:2:3", "1.5").forEach { assertNull(it, GoToLine.parse(it, 10)) }
    }

    @Test
    fun `offsets follow the line starts and clamp the column to the line`() {
        val text = "ab\ncde\n\nf"
        assertEquals(0, GoToLine.offsetOf(text, GoToTarget(0, 0)))
        assertEquals(1, GoToLine.offsetOf(text, GoToTarget(0, 1)))
        assertEquals(2, GoToLine.offsetOf(text, GoToTarget(0, 9)))
        assertEquals(5, GoToLine.offsetOf(text, GoToTarget(1, 2)))
        assertEquals(6, GoToLine.offsetOf(text, GoToTarget(1, 50)))
        assertEquals(7, GoToLine.offsetOf(text, GoToTarget(2, 0)))
        assertEquals(text.length, GoToLine.offsetOf(text, GoToTarget(3, 5)))
        assertEquals(text.length, GoToLine.offsetOf(text, GoToTarget(30, 0)))
    }
}
