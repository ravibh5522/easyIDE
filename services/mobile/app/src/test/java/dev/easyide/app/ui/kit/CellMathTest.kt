package dev.easyide.app.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CellMathTest {

    @Test fun `cells that fit count the gaps between them`() {
        assertEquals(10, cellCount(widthPx = 100f, cellPx = 8f, gapPx = 2f))
        assertEquals(10, cellCount(widthPx = 107f, cellPx = 8f, gapPx = 2f))
        assertEquals(11, cellCount(widthPx = 108f, cellPx = 8f, gapPx = 2f))
        assertEquals(0, cellCount(widthPx = 7f, cellPx = 8f, gapPx = 2f))
        assertEquals(0, cellCount(widthPx = 0f, cellPx = 8f, gapPx = 2f))
        assertEquals(0, cellCount(widthPx = 100f, cellPx = 0f, gapPx = 2f))
    }

    @Test fun `fill is floored and clamped`() {
        assertEquals(0, filledCells(0f, 10))
        assertEquals(10, filledCells(1f, 10))
        assertEquals(4, filledCells(0.49f, 10))
        assertEquals(0, filledCells(-1f, 10))
        assertEquals(10, filledCells(2f, 10))
        assertEquals(0, filledCells(Float.NaN, 10))
        assertEquals(0, filledCells(0.5f, 0))
    }

    @Test fun `a fraction that is a whole number of cells is not lost to float error`() {
        for (cells in listOf(7, 10, 20, 33)) {
            for (k in 0..cells) assertEquals("$k of $cells", k, filledCells(k.toFloat() / cells, cells))
        }
    }

    @Test fun `fill never decreases as the fraction grows and never reaches full early`() {
        val cells = 24
        var last = 0
        for (i in 0..1000) {
            val f = i / 1000f
            val n = filledCells(f, cells)
            assertTrue(n >= last)
            if (f < 1f - 1f / cells) assertTrue(n < cells)
            last = n
        }
    }

    @Test fun `the sweep stays hidden for the first 300ms then steps every 90ms`() {
        assertEquals(Sweep.HIDDEN, Sweep.cellAt(0))
        assertEquals(Sweep.HIDDEN, Sweep.cellAt(299))
        assertEquals(0, Sweep.cellAt(300))
        assertEquals(0, Sweep.cellAt(389))
        assertEquals(1, Sweep.cellAt(390))
        assertEquals(7, Sweep.cellAt(300 + 7 * 90))
    }

    @Test fun `the sweep wraps after eight cells and stays in range for any elapsed time`() {
        assertEquals(0, Sweep.cellAt(300 + 8 * 90))
        assertEquals(1, Sweep.cellAt(300 + 9 * 90))
        for (t in 0L..20_000L step 37L) assertTrue(Sweep.cellAt(t) in Sweep.HIDDEN until Sweep.CELLS)
    }
}
