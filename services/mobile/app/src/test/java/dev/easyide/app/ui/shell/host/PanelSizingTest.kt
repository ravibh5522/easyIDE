package dev.easyide.app.ui.shell.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelSizingTest {
    @Test fun `an untouched panel takes its default`() {
        assertEquals(280f, PanelSizing.width(null, 280f, 1200f), 0f)
    }

    @Test fun `a dragged size is kept inside the limits and the window share`() {
        assertEquals(450f, PanelSizing.width(1000f, 280f, 1000f), 0f)
        assertEquals(PanelSizing.limits.min, PanelSizing.width(10f, 280f, 1000f), 0f)
    }

    @Test fun `a window that shrinks shrinks the panel but never below the minimum`() {
        assertEquals(220f, PanelSizing.width(400f, 280f, 500f), 0f)
        assertEquals(PanelSizing.limits.min, PanelSizing.width(400f, 280f, 300f), 0f)
    }

    @Test fun `the ceiling always allows at least the minimum`() {
        assertTrue(PanelSizing.ceiling(100f) >= PanelSizing.limits.min)
    }
}
