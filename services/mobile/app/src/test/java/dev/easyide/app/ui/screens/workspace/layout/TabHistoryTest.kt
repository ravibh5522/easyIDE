package dev.easyide.app.ui.screens.workspace.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabHistoryTest {

    private fun history(vararg activations: String): TabHistory {
        val h = TabHistory()
        val open = mutableListOf<String>()
        activations.forEach { path ->
            if (path !in open) open += path
            h.sync(open, path)
        }
        return h
    }

    @Test
    fun `activating a tab moves it to the front`() {
        val h = history("a", "b", "c")
        assertEquals(listOf("c", "b", "a"), h.recent)
        h.sync(listOf("a", "b", "c"), "a")
        assertEquals(listOf("a", "c", "b"), h.recent)
    }

    @Test
    fun `closed tabs leave the history and unseen tabs join at the back`() {
        val h = history("a", "b", "c")
        h.sync(listOf("a", "c", "d"), "c")
        assertEquals(listOf("c", "a", "d"), h.recent)
    }

    @Test
    fun `closing the active tab falls back to the most recently used`() {
        val h = history("a", "b", "c", "b")
        val tabs = listOf("a", "b", "c")
        assertEquals("c", h.activeAfterClosing(setOf("b"), "b", tabs))
    }

    @Test
    fun `closing another tab keeps the active one`() {
        val h = history("a", "b")
        assertEquals("b", h.activeAfterClosing(setOf("a"), "b", listOf("a", "b")))
    }

    @Test
    fun `without history the right neighbour takes over, then the left`() {
        val h = TabHistory()
        val tabs = listOf("a", "b", "c")
        assertEquals("c", h.activeAfterClosing(setOf("b"), "b", tabs))
        assertEquals("b", h.activeAfterClosing(setOf("c"), "c", tabs))
        assertNull(h.activeAfterClosing(setOf("a", "b", "c"), "a", tabs))
    }

    @Test
    fun `closing several tabs picks the most recent survivor`() {
        val h = history("a", "b", "c", "d")
        assertEquals("b", h.activeAfterClosing(setOf("d", "c"), "d", listOf("a", "b", "c", "d")))
    }

    @Test
    fun `a single press flips between the two latest tabs`() {
        val h = history("a", "b", "c")
        val open = listOf("a", "b", "c")
        assertEquals("b", h.cycle(1, nowMs = 0))
        h.sync(open, "b")
        assertEquals("c", h.cycle(1, nowMs = 5_000))
        h.sync(open, "c")
        assertEquals("b", h.cycle(1, nowMs = 10_000))
    }

    @Test
    fun `quick presses walk deeper into the history`() {
        val h = history("a", "b", "c")
        val open = listOf("a", "b", "c")
        assertEquals("b", h.cycle(1, 0)); h.sync(open, "b")
        assertEquals("a", h.cycle(1, 300)); h.sync(open, "a")
        assertEquals("c", h.cycle(1, 600)); h.sync(open, "c")
    }

    @Test
    fun `reverse cycling starts from the least recently used`() {
        val h = history("a", "b", "c")
        assertEquals("a", h.cycle(-1, 0))
    }

    @Test
    fun `an unrelated activation ends a cycling gesture`() {
        val h = history("a", "b", "c")
        val open = listOf("a", "b", "c")
        h.cycle(1, 0); h.sync(open, "b")
        h.sync(open, "a")
        assertEquals("c", h.cycle(1, 100))
    }

    @Test
    fun `cycling needs two tabs`() {
        assertNull(history("a").cycle(1, 0))
        assertNull(TabHistory().cycle(1, 0))
    }
}
