package dev.easyide.app.ui.screens.settings

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeGridTest {

    private val card = 152.dp
    private val gap = 12.dp

    @Test fun `as many equal columns as fit the card width plus the gaps`() {
        assertEquals(3, themeColumns(card * 3 + gap * 2, card, gap))
        assertEquals(2, themeColumns(card * 3 + gap * 2 - 1.dp, card, gap))
        assertEquals(6, themeColumns(1000.dp, card, gap))
    }

    @Test fun `a window narrower than one card still gets one column`() {
        assertEquals(1, themeColumns(100.dp, card, gap))
        assertEquals(1, themeColumns(0.dp, card, gap))
    }
}
