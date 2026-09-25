package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.ui.foundation.WidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusPlanTest {
    private fun slot(id: StatusId, width: Float, min: Float = width) = StatusSlot(id, width, min)

    @Test fun `everything shows when everything fits`() {
        val slots = listOf(slot(StatusId.PROJECT, 80f), slot(StatusId.FILE, 100f), slot(StatusId.PROBLEMS, 60f))
        val plan = StatusPlan.fit(slots, 1000f, 8f)
        assertEquals(setOf(StatusId.PROJECT, StatusId.FILE, StatusId.PROBLEMS), plan.keys)
        assertEquals(100f, plan.getValue(StatusId.FILE), 0f)
    }

    @Test fun `the lowest priority goes first when the strip is short`() {
        val slots = StatusId.entries.map { slot(it, 50f) }
        val plan = StatusPlan.fit(slots, 4 * 50f + 3 * 8f, 8f)
        assertEquals(setOf(StatusId.PROBLEMS, StatusId.SAVE, StatusId.FILE, StatusId.EXT_RIGHT), plan.keys)
    }

    @Test fun `parts never overlap`() {
        val slots = StatusId.entries.map { slot(it, 70f, min = 30f) }
        listOf(20f, 100f, 250f, 400f, 1000f).forEach { available ->
            val plan = StatusPlan.fit(slots, available, 8f)
            val used = plan.values.sum() + 8f * (plan.size - 1).coerceAtLeast(0)
            assertTrue("$available -> $used", used <= available + 0.001f)
        }
    }

    @Test fun `a name is squeezed before a part that cannot shrink is dropped`() {
        val slots = listOf(slot(StatusId.PROBLEMS, 60f), slot(StatusId.FILE, 200f, min = 40f))
        val plan = StatusPlan.fit(slots, 150f, 8f)
        assertEquals(60f, plan.getValue(StatusId.PROBLEMS), 0f)
        assertEquals(82f, plan.getValue(StatusId.FILE), 0f)
    }

    @Test fun `a part that cannot fit even squeezed is dropped whole`() {
        val plan = StatusPlan.fit(listOf(slot(StatusId.PROBLEMS, 60f), slot(StatusId.EXT_LEFT, 80f)), 100f, 8f)
        assertEquals(setOf(StatusId.PROBLEMS), plan.keys)
    }

    @Test fun `spare width goes to the higher priority name first`() {
        val slots = listOf(slot(StatusId.FILE, 100f, min = 20f), slot(StatusId.PROJECT, 100f, min = 20f))
        val plan = StatusPlan.fit(slots, 150f, 10f)
        assertEquals(100f, plan.getValue(StatusId.FILE), 0f)
        assertEquals(40f, plan.getValue(StatusId.PROJECT), 0f)
    }

    @Test fun `nothing fits in no width`() {
        assertTrue(StatusPlan.fit(listOf(slot(StatusId.FILE, 10f)), 0f, 8f).isEmpty())
    }

    @Test fun `a phone considers neither the project name nor the line count`() {
        val phone = StatusPlan.allowed(WidthClass.COMPACT)
        assertFalse(StatusId.PROJECT in phone)
        assertFalse(StatusId.LINES in phone)
        assertTrue(StatusId.PROBLEMS in phone && StatusId.FILE in phone && StatusId.SAVE in phone)
    }

    @Test fun `medium adds lines and expanded adds the project`() {
        assertTrue(StatusId.LINES in StatusPlan.allowed(WidthClass.MEDIUM))
        assertFalse(StatusId.PROJECT in StatusPlan.allowed(WidthClass.MEDIUM))
        assertEquals(StatusId.entries.toSet(), StatusPlan.allowed(WidthClass.EXPANDED))
    }
}
