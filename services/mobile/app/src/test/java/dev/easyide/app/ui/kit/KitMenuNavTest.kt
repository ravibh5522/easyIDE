package dev.easyide.app.ui.kit

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KitMenuNavTest {
    private fun action(label: String, enabled: Boolean = true) = KitMenuItem.Action(label, {}, enabled = enabled)

    private val inner = listOf(action("a"), action("b", enabled = false), action("c"))
    private val root = listOf(
        action("open"),
        KitMenuItem.Divider,
        action("locked", enabled = false),
        KitMenuItem.Submenu("checkout", inner),
        KitMenuItem.Submenu("empty", emptyList()),
        action("last"),
    )

    @Test fun `first Down lands on the first entry and skips dividers and disabled rows`() {
        val a = MenuNav().move(root, 1)
        assertEquals(listOf(0), a.path)
        assertEquals(listOf(3), a.move(root, 1).path)
    }

    @Test fun `Up from nothing lands on the last entry and both directions wrap`() {
        assertEquals(listOf(5), MenuNav().move(root, -1).path)
        assertEquals(listOf(0), MenuNav(listOf(5)).move(root, 1).path)
        assertEquals(listOf(5), MenuNav(listOf(0)).move(root, -1).path)
    }

    @Test fun `an empty submenu is disabled and never focused or opened`() {
        assertTrue(!(root[4] as KitMenuItem.Submenu).enabled)
        assertEquals(listOf(5), MenuNav(listOf(3)).move(root, 1).path)
        assertEquals(MenuNav(listOf(4)), MenuNav(listOf(4)).open(root))
    }

    @Test fun `Right opens a submenu on its first enabled entry and Left closes it`() {
        val open = MenuNav(listOf(3)).open(root)
        assertEquals(listOf(3, 0), open.path)
        assertEquals("a", (open.focused(root) as KitMenuItem.Action).label)
        assertEquals(listOf(3, 2), open.move(root, 1).path)
        assertEquals(listOf(3), open.close().path)
        assertEquals(listOf(3), open.close().close().path)
    }

    @Test fun `Right on a plain row does nothing`() {
        assertEquals(MenuNav(listOf(0)), MenuNav(listOf(0)).open(root))
    }

    @Test fun `hovering a submenu row opens it with nothing focused inside and hovering elsewhere closes it`() {
        val hovered = MenuNav().hover(root, 0, 3)
        assertEquals(listOf(3, MenuNav.NOTHING), hovered.path)
        assertNull(hovered.focused(root))
        assertEquals(listOf(0), hovered.hover(root, 0, 0).path)
        assertEquals(listOf(3, 2), hovered.hover(root, 1, 2).path)
    }

    @Test fun `Home and End go to the first and last focusable entry of the deepest level`() {
        assertEquals(listOf(5), MenuNav(listOf(0)).edge(root, last = true).path)
        assertEquals(listOf(3, 2), MenuNav(listOf(3, 0)).edge(root, last = true).path)
        assertEquals(listOf(3, 0), MenuNav(listOf(3, 2)).edge(root, last = false).path)
    }

    @Test fun `a menu with nothing enabled cannot be navigated`() {
        val dead = listOf(action("x", enabled = false), KitMenuItem.Divider)
        assertEquals(-1, nextFocusable(dead, -1, 1))
        assertEquals(MenuNav(), MenuNav().move(dead, 1))
    }

    @Test fun `tidy cleans dividers inside submenus too`() {
        val messy = listOf(KitMenuItem.Divider, KitMenuItem.Submenu("s", listOf(action("a"), KitMenuItem.Divider, KitMenuItem.Divider)), KitMenuItem.Divider)
        val tidy = tidyMenu(messy)
        assertEquals(1, tidy.size)
        assertEquals(1, (tidy[0] as KitMenuItem.Submenu).items.size)
    }

    private val window = IntSize(1000, 800)
    private val popup = IntSize(200, 300)

    @Test fun `a submenu opens to the right of its parent, level with the row`() {
        val parent = IntRect(100, 100, 300, 400)
        assertEquals(IntOffset(300, 150), submenuPosition(IntRect(100, 150, 300, 178), parent, window, popup))
    }

    @Test fun `a submenu near the right edge flips to the left of its parent`() {
        val parent = IntRect(700, 100, 900, 400)
        assertEquals(IntOffset(500, 150), submenuPosition(IntRect(700, 150, 900, 178), parent, window, popup))
    }

    @Test fun `a submenu that fits on neither side opens below its row`() {
        val narrow = IntSize(320, 800)
        val parent = IntRect(60, 100, 260, 400)
        assertEquals(IntOffset(60, 178), submenuPosition(IntRect(60, 150, 260, 178), parent, narrow, popup))
    }

    @Test fun `a submenu near the bottom is moved up to stay inside the window`() {
        val parent = IntRect(100, 400, 300, 780)
        assertEquals(IntOffset(300, 500), submenuPosition(IntRect(100, 740, 300, 768), parent, window, popup))
    }
}
