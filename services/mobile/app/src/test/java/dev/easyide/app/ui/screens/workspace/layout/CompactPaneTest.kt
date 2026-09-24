package dev.easyide.app.ui.screens.workspace.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactPaneTest {

    @Test
    fun `no stage means the editor`() {
        assertEquals(CompactPane.EDITOR, CompactPane.of(StageVisibility(), explorerSelected = true))
        assertEquals(CompactPane.EDITOR, CompactPane.of(StageVisibility(right = true), explorerSelected = true))
    }

    @Test
    fun `the left stage is Files or Git by the selected panel`() {
        val left = StageVisibility(left = true)
        assertEquals(CompactPane.FILES, CompactPane.of(left, explorerSelected = true))
        assertEquals(CompactPane.GIT, CompactPane.of(left, explorerSelected = false))
    }

    @Test
    fun `the terminal wins if a stale state shows both`() {
        assertEquals(CompactPane.TERMINAL, CompactPane.of(StageVisibility(left = true, bottom = true), explorerSelected = true))
    }
}
