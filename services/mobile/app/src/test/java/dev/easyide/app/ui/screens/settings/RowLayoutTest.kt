package dev.easyide.app.ui.screens.settings

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowLayoutTest {

    private val inlineMin = 280.dp

    @Test fun `a wide row uses two columns whatever the text size`() {
        assertEquals(RowLayout.TWO_COLUMN, rowLayoutFor(600.dp, 1f, inlineMin))
        assertEquals(RowLayout.TWO_COLUMN, rowLayoutFor(600.dp, 2f, inlineMin))
    }

    @Test fun `a phone row keeps a short control beside its title`() {
        assertEquals(RowLayout.INLINE, rowLayoutFor(355.dp, 1f, inlineMin))
        assertEquals(RowLayout.INLINE, rowLayoutFor(304.dp, 1f, inlineMin))
    }

    @Test fun `a row that is narrow for its text stacks`() {
        assertEquals(RowLayout.STACKED, rowLayoutFor(264.dp, 1f, inlineMin))
        assertEquals(RowLayout.STACKED, rowLayoutFor(355.dp, 2f, inlineMin))
    }

    @Test fun `only the two column layout is not narrow`() {
        assertFalse(RowLayout.TWO_COLUMN.narrow)
        assertTrue(RowLayout.INLINE.narrow)
        assertTrue(RowLayout.STACKED.narrow)
    }
}
