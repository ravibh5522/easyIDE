package dev.easyide.app.ui.screens.workspace.decor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupPlacementTest {

    private val viewportW = 800
    private val viewportH = 600
    private val margin = 4
    private val gap = 2

    private fun place(anchor: PopupAnchor, w: Int, h: Int, preferAbove: Boolean = false) =
        PopupPlacement.place(anchor, w, h, viewportW, viewportH, margin, gap, preferAbove)

    @Test fun `goes below the anchor line when it fits`() {
        assertEquals(PopupPosition(100, 122), place(PopupAnchor(100, 100, 120), 200, 150))
    }

    @Test fun `flips above when only the space above fits`() {
        val anchor = PopupAnchor(100, 500, 520)
        assertEquals(PopupPosition(100, 500 - gap - 150), place(anchor, 200, 150))
    }

    @Test fun `takes the taller side when neither fits`() {
        val anchor = PopupAnchor(10, 400, 420)
        val height = PopupPlacement.maxHeight(anchor, viewportH, margin, gap)
        assertEquals(400 - gap - margin, height)
        assertEquals(PopupPosition(10, margin), place(anchor, 200, height))
    }

    @Test fun `prefer-above stays above when it fits and flips below when it does not`() {
        assertEquals(PopupPosition(50, 300 - gap - 100), place(PopupAnchor(50, 300, 320), 100, 100, preferAbove = true))
        assertEquals(PopupPosition(50, 42), place(PopupAnchor(50, 20, 40), 100, 100, preferAbove = true))
    }

    @Test fun `is pushed left to keep its right edge inside`() {
        assertEquals(viewportW - margin - 300, place(PopupAnchor(700, 10, 30), 300, 50).x)
    }

    @Test fun `never starts left of the margin`() {
        assertEquals(margin, place(PopupAnchor(-40, 10, 30), 100, 50).x)
    }

    @Test fun `a popup wider than the viewport pins to the left margin`() {
        assertEquals(margin, place(PopupAnchor(300, 10, 30), viewportW * 2, 50).x)
        assertEquals(viewportW - 2 * margin, PopupPlacement.maxWidth(viewportW, margin))
    }

    @Test fun `a tiny viewport yields non-negative limits`() {
        val anchor = PopupAnchor(0, 0, 20)
        assertEquals(0, PopupPlacement.maxWidth(5, margin))
        assertEquals(0, PopupPlacement.maxHeight(anchor, 10, margin, gap))
        val position = PopupPlacement.place(anchor, 0, 0, 5, 10, margin, gap, preferAbove = false)
        assertEquals(PopupPosition(margin, 10 - margin), position)
    }

    @Test fun `an anchor scrolled out of view is not visible`() {
        assertTrue(PopupPlacement.isVisible(PopupAnchor(0, -10, 5), viewportH))
        assertFalse(PopupPlacement.isVisible(PopupAnchor(0, -30, 0), viewportH))
        assertFalse(PopupPlacement.isVisible(PopupAnchor(0, viewportH, viewportH + 20), viewportH))
    }
}
