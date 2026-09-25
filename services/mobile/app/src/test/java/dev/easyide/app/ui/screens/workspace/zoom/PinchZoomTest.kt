package dev.easyide.app.ui.screens.workspace.zoom

import org.junit.Assert.assertEquals
import org.junit.Test

class PinchZoomTest {

    @Test
    fun `scales and rounds to a whole size`() {
        assertEquals(13, PinchZoom.fontSize(13, 1.0f, 8, 32))
        assertEquals(20, PinchZoom.fontSize(13, 1.5f, 8, 32))
        assertEquals(10, PinchZoom.fontSize(13, 0.75f, 8, 32))
        assertEquals(13, PinchZoom.fontSize(13, 1.03f, 8, 32))
    }

    @Test
    fun `stays within the schema bounds`() {
        assertEquals(32, PinchZoom.fontSize(13, 10f, 8, 32))
        assertEquals(8, PinchZoom.fontSize(13, 0.1f, 8, 32))
    }
}
