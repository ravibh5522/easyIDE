package dev.easyide.app.ui.screens.workspace.decor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class IndentGuidesTest {

    @Test fun `columns count spaces and tabs and mark blank lines`() {
        val text = "a\n    b\n\t\tc\n   \n"
        fun cols(start: Int, end: Int) = IndentGuides.columns(text, start, end, 4)
        assertEquals(0, cols(0, 1))
        assertEquals(4, cols(2, 7))
        assertEquals(8, cols(8, 11))
        assertEquals(-1, cols(12, 15))
    }

    @Test fun `guides are one per full indent level`() {
        assertArrayEquals(intArrayOf(0, 0, 1, 2), IndentGuides.levels(intArrayOf(0, 3, 4, 9), 4))
    }

    @Test fun `a blank line continues the guides its neighbours share`() {
        assertArrayEquals(intArrayOf(2, 2, 2), IndentGuides.levels(intArrayOf(8, -1, 8), 4))
        assertArrayEquals(intArrayOf(1, 0, 0), IndentGuides.levels(intArrayOf(4, -1, 0), 4))
        assertArrayEquals(intArrayOf(0, 0), IndentGuides.levels(intArrayOf(-1, -1), 4))
    }
}
