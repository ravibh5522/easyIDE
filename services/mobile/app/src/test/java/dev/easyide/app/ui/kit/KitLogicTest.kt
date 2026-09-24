package dev.easyide.app.ui.kit

import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.props.Appearance
import dev.easyide.app.ui.props.Density
import dev.easyide.app.ui.props.Motif
import dev.easyide.app.ui.props.UiMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KitLogicTest {

    @Test fun `motif levels gate the supporting motifs and the art`() {
        assertFalse(Motif.OFF.drawsSupporting)
        assertTrue(Motif.SUBTLE.drawsSupporting)
        assertTrue(Motif.FULL.drawsSupporting)
        assertFalse(Motif.OFF.drawsArt)
        assertFalse(Motif.SUBTLE.drawsArt)
        assertTrue(Motif.FULL.drawsArt)
    }

    @Test fun `the blink is on for the first half of a period and off for the second`() {
        assertTrue(blinkOn(0f))
        assertTrue(blinkOn(0.499f))
        assertFalse(blinkOn(0.5f))
        assertFalse(blinkOn(0.999f))
    }

    @Test fun `a row follows its width class at every density but never drops below the touch floor`() {
        val floor = UiMetrics.TOUCH_FLOOR
        for (density in Density.entries) {
            val control = UiMetrics.of(Appearance(density = density)).control
            for (width in WidthClass.entries) {
                val h = rowMinHeight(control, width, floor)
                assertTrue("$density $width", h >= floor)
                assertEquals(maxOf(control.listRow(width), floor), h)
            }
        }
    }

    @Test fun `a comfortable compact row keeps its 48dp and an expanded one is raised to 44dp`() {
        val control = UiMetrics.DEFAULT.control
        assertEquals(48.dp, rowMinHeight(control, WidthClass.COMPACT, UiMetrics.TOUCH_FLOOR))
        assertEquals(44.dp, rowMinHeight(control, WidthClass.EXPANDED, UiMetrics.TOUCH_FLOOR))
    }
}
