package dev.easyide.app.ui.screens.workspace.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageVisibilityTest {

    @Test
    fun `free rule lets everything show`() {
        val all = StageVisibility().show(Stage.LEFT, StageRule.FREE)
            .show(Stage.RIGHT, StageRule.FREE).show(Stage.BOTTOM, StageRule.FREE)
        assertEquals(StageVisibility(left = true, right = true, bottom = true), all)
    }

    @Test
    fun `one side rule closes the other side when one opens`() {
        val leftOpen = StageVisibility(left = true)
        assertEquals(StageVisibility(right = true), leftOpen.show(Stage.RIGHT, StageRule.ONE_SIDE))
        val rightOpen = StageVisibility(right = true, bottom = true)
        assertEquals(StageVisibility(left = true, bottom = true), rightOpen.show(Stage.LEFT, StageRule.ONE_SIDE))
    }

    @Test
    fun `one pane rule keeps only the stage being opened`() {
        val s = StageVisibility(left = true).show(Stage.BOTTOM, StageRule.ONE_PANE)
        assertEquals(StageVisibility(bottom = true), s)
        assertEquals(StageVisibility(left = true), s.show(Stage.LEFT, StageRule.ONE_PANE))
    }

    @Test
    fun `a resize into one pane keeps the highest priority stage`() {
        val all = StageVisibility(left = true, right = true, bottom = true)
        assertEquals(StageVisibility(left = true), all.constrain(StageRule.ONE_PANE))
        assertEquals(StageVisibility(bottom = true), StageVisibility(right = true, bottom = true).constrain(StageRule.ONE_PANE))
    }

    @Test
    fun `toggling the open stage closes it`() {
        assertEquals(StageVisibility(), StageVisibility(left = true).toggle(Stage.LEFT, StageRule.ONE_PANE))
    }

    @Test
    fun `pinned stages cannot be hidden`() {
        assertTrue(StageVisibility(left = true).hide(Stage.LEFT, StageRule.LEFT_PINNED).left)
        assertTrue(StageVisibility().constrain(StageRule.LEFT_PINNED).left)
        assertTrue(StageVisibility(bottom = true).toggle(Stage.BOTTOM, StageRule.BOTTOM_PINNED).bottom)
        assertFalse(StageVisibility().constrain(StageRule.BOTTOM_PINNED).left)
    }

    @Test
    fun `pinned book keeps the right stage available`() {
        val s = StageVisibility().constrain(StageRule.LEFT_PINNED).show(Stage.RIGHT, StageRule.LEFT_PINNED)
        assertEquals(StageVisibility(left = true, right = true), s)
    }
}
