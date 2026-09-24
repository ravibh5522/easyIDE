package dev.easyide.app.ui.screens.workspace.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class TabOrderTest {

    @Test
    fun `move places the element at the target index`() {
        assertEquals(listOf("b", "c", "a"), TabOrder.move(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), TabOrder.move(listOf("a", "b", "c"), 2, 0))
    }

    @Test
    fun `move ignores no-ops and bad indices`() {
        val list = listOf("a", "b")
        assertEquals(list, TabOrder.move(list, 1, 1))
        assertEquals(list, TabOrder.move(list, -1, 0))
        assertEquals(list, TabOrder.move(list, 0, 2))
        assertEquals(emptyList<String>(), TabOrder.move(emptyList<String>(), 0, 0))
    }

    private val paths = listOf("a", "b", "c", "d")

    @Test
    fun `close sets`() {
        assertEquals(listOf("b"), TabOrder.closeSet(paths, "b", CloseScope.THIS))
        assertEquals(listOf("a", "c", "d"), TabOrder.closeSet(paths, "b", CloseScope.OTHERS))
        assertEquals(listOf("c", "d"), TabOrder.closeSet(paths, "b", CloseScope.TO_THE_RIGHT))
        assertEquals(paths, TabOrder.closeSet(paths, "b", CloseScope.ALL))
        assertEquals(emptyList<String>(), TabOrder.closeSet(paths, "d", CloseScope.TO_THE_RIGHT))
        assertEquals(emptyList<String>(), TabOrder.closeSet(paths, "zzz", CloseScope.ALL))
    }

    private val widths = listOf(100f, 100f, 100f)

    @Test
    fun `drop index follows the dragged centre`() {
        assertEquals(0, TabOrder.dropIndex(widths, from = 0, offset = 10f))
        assertEquals(1, TabOrder.dropIndex(widths, from = 0, offset = 60f))
        assertEquals(2, TabOrder.dropIndex(widths, from = 0, offset = 400f))
        assertEquals(0, TabOrder.dropIndex(widths, from = 2, offset = -400f))
        assertEquals(1, TabOrder.dropIndex(widths, from = 2, offset = -100f))
    }

    @Test
    fun `drop index copes with uneven widths and an empty strip`() {
        assertEquals(1, TabOrder.dropIndex(listOf(50f, 200f, 50f), from = 0, offset = 100f))
        assertEquals(0, TabOrder.dropIndex(emptyList(), 0, 0f))
    }

    @Test
    fun `scroll to reveal`() {
        assertEquals(0f, TabOrder.scrollToReveal(10f, 90f, 0f, 100f), 0f)
        assertEquals(-20f, TabOrder.scrollToReveal(-20f, 60f, 0f, 100f), 0f)
        assertEquals(30f, TabOrder.scrollToReveal(60f, 130f, 0f, 100f), 0f)
        assertEquals(50f, TabOrder.scrollToReveal(50f, 400f, 0f, 100f), 0f)
    }
}
