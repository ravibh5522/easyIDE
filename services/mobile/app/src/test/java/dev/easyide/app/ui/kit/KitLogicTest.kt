package dev.easyide.app.ui.kit

import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.shell.nav.NavPlacement
import dev.easyide.app.ui.shell.nav.NavRules
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

    @Test fun `a row is one line unless asked, and a second line needs comfort or a selection`() {
        assertEquals(RowLines.One, rowLines(Density.DENSE, hasDescription = true, secondLine = false, selected = false))
        assertEquals(RowLines.One, rowLines(Density.DENSE, hasDescription = true, secondLine = true, selected = false))
        assertEquals(RowLines.Two, rowLines(Density.DENSE, hasDescription = true, secondLine = true, selected = true))
        assertEquals(RowLines.Two, rowLines(Density.COMFORTABLE, hasDescription = true, secondLine = true, selected = false))
        assertEquals(RowLines.One, rowLines(Density.COMFORTABLE, hasDescription = true, secondLine = false, selected = false))
        assertEquals(RowLines.One, rowLines(Density.SPACIOUS, hasDescription = false, secondLine = true, selected = true))
    }

    @Test fun `the text edge is the same for every row with the same columns`() {
        val gap = 4.dp
        assertEquals(10.dp, rowTextEdge(10.dp, 12.dp, 0, twistie = false, leading = false, gap = gap))
        assertEquals(10.dp + 16.dp + 4.dp + 20.dp + 4.dp, rowTextEdge(10.dp, 12.dp, 0, twistie = true, leading = true, gap = gap))
        assertEquals(10.dp + 24.dp + 16.dp + 4.dp, rowTextEdge(10.dp, 12.dp, 2, twistie = true, leading = false, gap = gap))
    }

    @Test fun `the description takes what the title leaves and is dropped when only a stub would fit`() {
        assertEquals(100, descriptionWidth(available = 200, titleWidth = 92, gap = 8, minimum = 40))
        assertEquals(0, descriptionWidth(available = 200, titleWidth = 155, gap = 8, minimum = 40))
        assertEquals(0, descriptionWidth(available = 200, titleWidth = 200, gap = 8, minimum = 40))
        assertEquals(40, descriptionWidth(available = 200, titleWidth = 152, gap = 8, minimum = 40))
    }

    @Test fun `a slot never makes the row taller than the row token`() {
        assertEquals(28, slotHeight(content = 32, row = 28))
        assertEquals(20, slotHeight(content = 20, row = 28))
    }

    @Test fun `a two column row stacks below the threshold and its label column is clamped`() {
        assertTrue(twoColumnStacked(479.dp, 480.dp))
        assertFalse(twoColumnStacked(480.dp, 480.dp))
        assertEquals(160.dp, labelColumnWidth(300.dp))
        assertEquals(360.dp, labelColumnWidth(1000.dp))
        assertEquals(270.dp, labelColumnWidth(600.dp))
    }

    @Test fun `segments are as wide as their labels and share the spare width, or keep natural widths when short`() {
        assertEquals(listOf(55, 95), segmentWidths(listOf(40, 80), 150))
        assertEquals(listOf(41, 40), segmentWidths(listOf(30, 30), 81))
        assertEquals(listOf(40, 80), segmentWidths(listOf(40, 80), 100))
        assertEquals(emptyList<Int>(), segmentWidths(emptyList(), 100))
        assertTrue(dividerAfter(0, 3))
        assertFalse(dividerAfter(2, 3))
    }

    @Test fun `the nav cell is the bar height in a bar or with labels and the rail width for a bare rail`() {
        val control = UiMetrics.of(Appearance(density = Density.DENSE)).control
        assertEquals(56.dp, NavRules.cellHeight(NavPlacement.BOTTOM, labelled = true, control))
        assertEquals(56.dp, NavRules.cellHeight(NavPlacement.RAIL_START, labelled = true, control))
        assertEquals(52.dp, NavRules.cellHeight(NavPlacement.RAIL_START, labelled = false, control))
    }
}
