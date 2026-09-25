package dev.easyide.app.ui.screens.workspace.layout

import dev.easyide.app.ui.foundation.FoldPosture
import dev.easyide.app.ui.foundation.HingeAxis
import dev.easyide.app.ui.foundation.WidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaneArrangementTest {

    private fun fold(axis: HingeAxis, halfOpened: Boolean = false, separating: Boolean = false) =
        FoldPosture(axis, halfOpened, separating, occludes = false, left = 0, top = 0, right = 0, bottom = 0)

    @Test
    fun `width class alone decides on a rigid screen`() {
        assertEquals(PaneArrangement.SINGLE_PANE, PaneArrangement.of(WidthClass.COMPACT, null))
        assertEquals(PaneArrangement.ONE_SIDE, PaneArrangement.of(WidthClass.MEDIUM, null))
        assertEquals(PaneArrangement.FULL, PaneArrangement.of(WidthClass.EXPANDED, null))
    }

    @Test
    fun `a half-opened vertical hinge is book posture whatever the width`() {
        val book = fold(HingeAxis.VERTICAL, halfOpened = true)
        assertEquals(PaneArrangement.BOOK, PaneArrangement.of(WidthClass.EXPANDED, book))
        assertEquals(PaneArrangement.BOOK, PaneArrangement.of(WidthClass.MEDIUM, book))
    }

    @Test
    fun `a half-opened horizontal hinge is tabletop`() {
        assertEquals(PaneArrangement.TABLETOP, PaneArrangement.of(WidthClass.MEDIUM, fold(HingeAxis.HORIZONTAL, halfOpened = true)))
    }

    @Test
    fun `a separating hinge splits even when flat`() {
        assertEquals(PaneArrangement.BOOK, PaneArrangement.of(WidthClass.EXPANDED, fold(HingeAxis.VERTICAL, separating = true)))
    }

    @Test
    fun `a flat non-separating fold is ignored`() {
        assertEquals(PaneArrangement.FULL, PaneArrangement.of(WidthClass.EXPANDED, fold(HingeAxis.VERTICAL)))
        assertEquals(PaneArrangement.SINGLE_PANE, PaneArrangement.of(WidthClass.COMPACT, fold(HingeAxis.HORIZONTAL)))
    }

    @Test
    fun `an occluding hinge leaves a gap and a crease splits at its centre`() {
        assertEquals(HingeSplit(first = 500, gap = 40, second = 460), HingeSplit.around(500, 540, occludes = true, origin = 0, size = 1000))
        assertEquals(HingeSplit(first = 500, gap = 0, second = 500), HingeSplit.around(499, 501, occludes = false, origin = 0, size = 1000))
    }

    @Test
    fun `the split is relative to the container origin`() {
        assertEquals(HingeSplit(first = 400, gap = 0, second = 600), HingeSplit.around(599, 601, occludes = false, origin = 200, size = 1000))
    }

    @Test
    fun `a hinge outside the container gives no split`() {
        assertNull(HingeSplit.around(1200, 1240, occludes = true, origin = 0, size = 1000))
        assertNull(HingeSplit.around(0, 10, occludes = true, origin = 0, size = 1000))
    }

    @Test
    fun `stage rules follow the arrangement`() {
        assertEquals(StageRule.ONE_PANE, StageRule.of(PaneArrangement.SINGLE_PANE))
        assertEquals(StageRule.ONE_SIDE, StageRule.of(PaneArrangement.ONE_SIDE))
        assertEquals(StageRule.FREE, StageRule.of(PaneArrangement.FULL))
        assertEquals(StageRule.LEFT_PINNED, StageRule.of(PaneArrangement.BOOK))
        assertEquals(StageRule.BOTTOM_PINNED, StageRule.of(PaneArrangement.TABLETOP))
    }
}
