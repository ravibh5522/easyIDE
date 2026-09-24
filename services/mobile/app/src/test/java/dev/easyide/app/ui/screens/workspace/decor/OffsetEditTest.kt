package dev.easyide.app.ui.screens.workspace.decor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OffsetEditTest {

    @Test fun `equal texts have no edit`() {
        assertNull(OffsetEdit.between("abc", "abc"))
        assertNull(OffsetEdit.between("", ""))
        val same = "x"
        assertNull(OffsetEdit.between(same, same))
    }

    @Test fun `insertion deletion and replacement are found from the shared ends`() {
        assertEquals(OffsetEdit(2, 2, 4), OffsetEdit.between("abcd", "abXYcd"))
        assertEquals(OffsetEdit(1, 3, 1), OffsetEdit.between("abcd", "ad"))
        assertEquals(OffsetEdit(1, 3, 2), OffsetEdit.between("abcd", "aZd"))
        assertEquals(OffsetEdit(0, 0, 3), OffsetEdit.between("", "abc"))
        assertEquals(OffsetEdit(0, 3, 0), OffsetEdit.between("abc", ""))
    }

    @Test fun `repeated characters resolve to the leftmost insertion point`() {
        // "aa" -> "aaa": the prefix scan takes both a's, so the edit is an append.
        assertEquals(OffsetEdit(2, 2, 3), OffsetEdit.between("aa", "aaa"))
    }

    @Test fun `an edit never starts or ends inside a surrogate pair`() {
        val grin = "😀"
        val joy = "😂" // same high surrogate as grin
        val edit = OffsetEdit.between("a${grin}b", "a${joy}b")!!
        assertEquals(OffsetEdit(1, 3, 3), edit)

        val sameLow = "🨀" // different high, same low surrogate as grin
        assertEquals(OffsetEdit(1, 3, 3), OffsetEdit.between("a${grin}b", "a${sameLow}b"))
    }

    @Test fun `mapAfter pushes an offset past text inserted at it`() {
        val insert = OffsetEdit(5, 5, 8)
        assertEquals(4, insert.mapAfter(4))
        assertEquals(8, insert.mapAfter(5))
        assertEquals(9, insert.mapAfter(6))
    }

    @Test fun `mapBefore leaves an offset in front of text inserted at it`() {
        val insert = OffsetEdit(5, 5, 8)
        assertEquals(5, insert.mapBefore(5))
        assertEquals(9, insert.mapBefore(6))
    }

    @Test fun `offsets inside a replacement collapse to its edges`() {
        val replace = OffsetEdit(2, 6, 3) // four chars became one
        assertEquals(3, replace.mapAfter(4))
        assertEquals(2, replace.mapBefore(4))
        assertEquals(3, replace.mapAfter(6))
        assertEquals(2, replace.mapBefore(6))
        assertEquals(5, replace.mapBefore(8))
    }

    @Test fun `touching a range edge is not overlap`() {
        val insertAtEdge = OffsetEdit(5, 5, 6)
        assertFalse(insertAtEdge.overlaps(5, 8))
        assertFalse(insertAtEdge.overlaps(2, 5))
        assertTrue(insertAtEdge.overlaps(4, 6))

        val deleteBefore = OffsetEdit(2, 5, 2)
        assertFalse(deleteBefore.overlaps(5, 8))
        assertTrue(deleteBefore.overlaps(4, 8))
    }

    @Test fun `a point overlaps only a replacement strictly around it`() {
        assertFalse(OffsetEdit(5, 5, 7).overlaps(5, 5))
        assertFalse(OffsetEdit(5, 7, 5).overlaps(5, 5))
        assertTrue(OffsetEdit(4, 7, 4).overlaps(5, 5))
    }
}
