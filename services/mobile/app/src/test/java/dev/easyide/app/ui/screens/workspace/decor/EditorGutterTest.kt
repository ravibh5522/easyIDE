package dev.easyide.app.ui.screens.workspace.decor

import androidx.compose.ui.unit.dp
import dev.easyide.app.data.settings.LineNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorGutterTest {

    @Test fun `logical lines are found by binary search`() {
        val starts = LineStarts("ab\n\ncde\nf")
        assertEquals(4, starts.count)
        assertEquals(listOf(0, 0, 0, 1, 2, 2, 2, 2, 3), (0..8).map(starts::lineOf))
        assertEquals(2, starts.startingAt(4))
        assertEquals(-1, starts.startingAt(5))
        assertEquals(4, starts.startOf(2))
    }

    @Test fun `an empty buffer has one line`() {
        val starts = LineStarts("")
        assertEquals(1, starts.count)
        assertEquals(0, starts.lineOf(0))
    }

    @Test fun `the gutter holds at least three digits, two when narrow, more for long files`() {
        assertEquals(3, EditorGutter.digits(9, compact = false))
        assertEquals(2, EditorGutter.digits(9, compact = true))
        assertEquals(4, EditorGutter.digits(1234, compact = true))
        assertEquals(6, EditorGutter.digits(123456, compact = false))
    }

    @Test fun `width is the glyph margin, the digits and one character of gap, no digits without numbers`() {
        assertEquals(18.dp + 8.dp * 4, EditorGutter.width(10, LineNumbers.ON, 8.dp, compact = false))
        assertEquals(14.dp + 8.dp * 3, EditorGutter.width(10, LineNumbers.RELATIVE, 8.dp, compact = true))
        assertEquals(18.dp + 8.dp, EditorGutter.width(10_000, LineNumbers.OFF, 8.dp, compact = false))
    }

    @Test fun `320dp is compact and a tablet is not`() {
        assertTrue(EditorGutter.isCompact(320.dp))
        assertFalse(EditorGutter.isCompact(360.dp))
        assertFalse(EditorGutter.isCompact(1152.dp))
    }

    @Test fun `relative numbers count from the caret line, which keeps its own number`() {
        assertEquals("8", EditorGutter.label(LineNumbers.ON, 7, 3))
        assertEquals("4", EditorGutter.label(LineNumbers.RELATIVE, 7, 3))
        assertEquals("4", EditorGutter.label(LineNumbers.RELATIVE, 3, 3))
        assertEquals("2", EditorGutter.label(LineNumbers.RELATIVE, 1, 3))
    }
}
