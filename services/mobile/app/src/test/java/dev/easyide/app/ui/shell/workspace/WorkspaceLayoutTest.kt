package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.ui.shell.BOOK
import dev.easyide.app.ui.shell.COMPACT
import dev.easyide.app.ui.shell.EXPANDED
import dev.easyide.app.ui.shell.LayoutPresets
import dev.easyide.app.ui.shell.MEDIUM
import dev.easyide.app.ui.shell.PHONE_LANDSCAPE
import dev.easyide.app.ui.shell.PanelLayout
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.TABLETOP
import dev.easyide.app.ui.screens.workspace.layout.StageVisibility
import dev.easyide.app.ui.foundation.WindowSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceLayoutTest {
    private val allOpen = PanelLayout(open = StageVisibility(left = true, right = true, bottom = true))
    private fun plan(window: WindowSize, layout: PanelLayout = allOpen) = WorkspaceLayout.plan(window, layout)

    @Test fun `a phone draws every open panel as a sheet`() {
        val p = plan(COMPACT)
        assertEquals(listOf(PanelMode.SHEET, PanelMode.SHEET, PanelMode.SHEET), listOf(p.sidebar, p.secondary, p.bottom))
    }

    @Test fun `a closed panel is hidden whatever the window`() {
        listOf(COMPACT, MEDIUM, EXPANDED, BOOK, TABLETOP).forEach { window ->
            assertEquals(PanelMode.HIDDEN, plan(window, PanelLayout()).sidebar)
        }
    }

    @Test fun `a phone in landscape docks its side panels but sheets the bottom one`() {
        val p = plan(PHONE_LANDSCAPE)
        assertEquals(PanelMode.DOCKED, p.sidebar)
        assertEquals(PanelMode.SHEET, p.bottom)
    }

    @Test fun `a tablet docks all three`() {
        val p = plan(EXPANDED)
        assertEquals(listOf(PanelMode.DOCKED, PanelMode.DOCKED, PanelMode.DOCKED), listOf(p.sidebar, p.secondary, p.bottom))
    }

    @Test fun `mode is read by placement`() {
        val p = plan(EXPANDED, PanelLayout(open = StageVisibility(bottom = true)))
        assertEquals(PanelMode.DOCKED, p.mode(Placement.PANEL))
        assertEquals(PanelMode.HIDDEN, p.mode(Placement.SIDEBAR))
    }

    @Test fun `postures pick their own arrangement`() {
        assertEquals(dev.easyide.app.ui.screens.workspace.layout.PaneArrangement.BOOK, plan(BOOK).arrangement)
        assertEquals(dev.easyide.app.ui.screens.workspace.layout.PaneArrangement.TABLETOP, plan(TABLETOP).arrangement)
    }

    @Test fun `every preset applied to every window leaves only panels its rule allows`() {
        listOf(COMPACT, PHONE_LANDSCAPE, MEDIUM, EXPANDED, BOOK, TABLETOP).forEach { window ->
            val arrangement = dev.easyide.app.ui.screens.workspace.layout.PaneArrangement.of(window.width, window.fold)
            LayoutPresets.offered(arrangement).forEach { preset ->
                val open = preset.applyTo(PanelLayout(), arrangement).let { l -> Placement.entries.count(l::isOpen) }
                if (arrangement == dev.easyide.app.ui.screens.workspace.layout.PaneArrangement.SINGLE_PANE) assertTrue("${preset.id} on a phone", open <= 1)
            }
        }
    }

    @Test fun `a phone's bottom sheet rests on a detent and is never shorter than the usable minimum`() {
        assertEquals(BottomSizing.HALF, BottomSizing.sheetFraction(null), 0f)
        assertEquals(400f, BottomSizing.sheetHeight(null, 800f), 0f)
        assertEquals(BottomSizing.MIN_USABLE_DP, BottomSizing.sheetHeight(BottomSizing.PEEK, 400f), 0f)
        assertEquals(800f, BottomSizing.sheetHeight(BottomSizing.FULL, 800f), 0f)
        assertEquals(120f, BottomSizing.sheetHeight(BottomSizing.FULL, 120f), 0f)
    }

    @Test fun `the keyboard shrinking the window keeps the detent, not the pixels`() {
        val stored = BottomSizing.settle(heightDp = 400f, availableDp = 800f)
        assertEquals(BottomSizing.HALF, stored, 0f)
        assertEquals(BottomSizing.HALF, BottomSizing.sheetFraction(stored), 0f)
        assertEquals(200f, BottomSizing.sheetHeight(stored, 400f), 0f)
    }

    @Test fun `a tap on the handle cycles the detents and wraps`() {
        assertEquals(BottomSizing.HALF, BottomSizing.nextFraction(BottomSizing.PEEK), 0f)
        assertEquals(BottomSizing.FULL, BottomSizing.nextFraction(BottomSizing.HALF), 0f)
        assertEquals(BottomSizing.PEEK, BottomSizing.nextFraction(BottomSizing.FULL), 0f)
    }

    @Test fun `a drag settles on the nearest detent`() {
        assertEquals(BottomSizing.PEEK, BottomSizing.settle(260f, 1000f), 0f)
        assertEquals(BottomSizing.HALF, BottomSizing.settle(600f, 1000f), 0f)
        assertEquals(BottomSizing.FULL, BottomSizing.settle(900f, 1000f), 0f)
    }

    @Test fun `a docked panel defaults to 35 percent and stays between a quarter and 70 percent`() {
        assertEquals(350f, BottomSizing.dockedHeight(null, 1000f), 0.5f)
        assertEquals(700f, BottomSizing.dockedHeight(900f, 1000f), 0.5f)
        assertEquals(250f, BottomSizing.dockedHeight(120f, 1000f), 0.5f)
    }

    @Test fun `a docked panel is never shorter than the usable minimum in a short window`() {
        assertTrue(BottomSizing.dockedHeight(null, 500f) >= BottomSizing.MIN_USABLE_DP)
    }

    @Test fun `a stored size of the wrong kind is ignored`() {
        assertEquals(350f, BottomSizing.dockedHeight(0.5f, 1000f), 0.5f)
        assertEquals(BottomSizing.HALF, BottomSizing.sheetFraction(400f), 0f)
    }
}
