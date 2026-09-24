package dev.easyide.app.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KitNavigationRulesTest {

    // --- tabs ---

    @Test fun `stepping moves by one and stops at the ends`() {
        assertEquals(2, stepTab(1, 4, 1))
        assertEquals(0, stepTab(1, 4, -1))
        assertEquals(0, stepTab(0, 4, -1))
        assertEquals(3, stepTab(3, 4, 1))
    }

    @Test fun `stepping an empty strip stays at zero`() {
        assertEquals(0, stepTab(0, 0, 1))
    }

    @Test fun `the indicator spans the label inside the padding`() {
        val span = indicatorSpan(tabWidth = 100f, inset = 16f)
        assertEquals(16f, span.start, 0f)
        assertEquals(84f, span.endInclusive, 0f)
    }

    @Test fun `a tab narrower than its padding gets a full width bar`() {
        val span = indicatorSpan(tabWidth = 20f, inset = 16f)
        assertEquals(0f, span.start, 0f)
        assertEquals(20f, span.endInclusive, 0f)
    }

    @Test fun `zero inset spans the whole tab`() {
        val span = indicatorSpan(tabWidth = 60f, inset = 0f)
        assertEquals(0f..60f, span)
    }

    @Test fun `segments have dividers between them and none after the last`() {
        assertTrue(dividerAfter(0, 3))
        assertTrue(dividerAfter(1, 3))
        assertFalse(dividerAfter(2, 3))
        assertFalse(dividerAfter(0, 1))
    }

    // --- stepper ---

    @Test fun `steps before the current are done and after it upcoming`() {
        assertEquals(
            listOf(StepState.Done, StepState.Done, StepState.Current, StepState.Upcoming),
            List(4) { stepState(it, 2) },
        )
    }

    @Test fun `the counter is one based and clamped`() {
        assertEquals("1/4", stepCounter(0, 4))
        assertEquals("4/4", stepCounter(3, 4))
        assertEquals("4/4", stepCounter(9, 4))
        assertEquals("1/4", stepCounter(-2, 4))
    }
}
