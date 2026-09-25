package dev.easyide.app.ui.screens.workspace

import dev.easyide.app.ui.screens.workspace.edit.TextState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoriesTest {

    @Test
    fun `history is per path and reports whether undo and redo are available`() {
        val h = EditHistories()
        assertFalse(h.canUndo("a"))
        h.record("a", TextState("", 0), TextState("x", 1), 0)
        assertTrue(h.canUndo("a"))
        assertFalse(h.canUndo("b"))
        assertFalse(h.canRedo("a"))
        assertEquals("", h.undo("a", "x")!!.text)
        assertFalse(h.canUndo("a"))
        assertTrue(h.canRedo("a"))
        assertEquals("x", h.redo("a", "").let { it!!.text })
    }

    @Test
    fun `renaming keeps history under the new path and closing drops it`() {
        val h = EditHistories()
        h.record("a", TextState("", 0), TextState("x", 1), 0)
        h.rename("a", "b")
        assertFalse(h.canUndo("a"))
        assertTrue(h.canUndo("b"))
        assertEquals("", h.undo("b", "x")!!.text)
        h.remove("b")
        assertNull(h.undo("b", ""))
        assertFalse(h.canRedo("b"))
    }

    @Test
    fun `isCurrent tells a recorded buffer from an unseen one`() {
        val h = EditHistories()
        assertFalse(h.isCurrent("a", "x"))
        h.record("a", TextState("", 0), TextState("x", 1), 0)
        assertTrue(h.isCurrent("a", "x"))
        assertFalse(h.isCurrent("a", "y"))
    }

    @Test
    fun `a selection is carried across an edit it did not report`() {
        // Text inserted before the caret pushes it; an edit after leaves it; an edit around it puts it at the end.
        assertEquals(TextState("XXabc", 5), EditHistories.carried(TextState("abc", 3), "XXabc"))
        assertEquals(TextState("abcXX", 1), EditHistories.carried(TextState("abc", 1), "abcXX"))
        assertEquals(TextState("aZ", 2, 2), EditHistories.carried(TextState("abc", 1, 2), "aZ"))
        assertEquals(TextState("same", 2), EditHistories.carried(TextState("same", 2), "same"))
    }
}
