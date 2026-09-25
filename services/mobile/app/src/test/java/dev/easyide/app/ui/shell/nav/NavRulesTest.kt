package dev.easyide.app.ui.shell.nav

import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.shell.IconRef
import dev.easyide.app.ui.shell.NavItem
import dev.easyide.app.ui.shell.NavTarget
import dev.easyide.app.ui.shell.ScopeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavRulesTest {
    private fun items(n: Int) = (1..n).map { NavItem("i$it", "Item $it", IconRef("x"), NavTarget.Container("c$it"), it, ScopeFilter.APP) }

    private fun ids(list: List<NavItem>) = list.map { it.id }

    @Test fun `auto is a bar on a phone and a rail on wider windows`() {
        assertEquals(NavPlacement.BOTTOM, NavRules.placement(NavPosition.AUTO, WidthClass.COMPACT))
        assertEquals(NavPlacement.RAIL_START, NavRules.placement(NavPosition.AUTO, WidthClass.MEDIUM))
        assertEquals(NavPlacement.RAIL_START, NavRules.placement(NavPosition.AUTO, WidthClass.EXPANDED))
    }

    @Test fun `an explicit position wins at any width`() {
        assertEquals(NavPlacement.RAIL_END, NavRules.placement(NavPosition.RIGHT, WidthClass.COMPACT))
        assertEquals(NavPlacement.RAIL_START, NavRules.placement(NavPosition.LEFT, WidthClass.COMPACT))
        assertEquals(NavPlacement.BOTTOM, NavRules.placement(NavPosition.BOTTOM, WidthClass.EXPANDED))
    }

    @Test fun `auto labels the bar always and a rail never`() {
        assertTrue(NavRules.showLabels(NavLabels.AUTO, NavPlacement.BOTTOM))
        assertFalse(NavRules.showLabels(NavLabels.AUTO, NavPlacement.RAIL_START))
        assertFalse(NavRules.showLabels(NavLabels.AUTO, NavPlacement.RAIL_END))
        assertTrue(NavRules.showLabels(NavLabels.ALWAYS, NavPlacement.RAIL_START))
        assertFalse(NavRules.showLabels(NavLabels.NEVER, NavPlacement.BOTTOM))
    }

    @Test fun `a rail keeps the workspace destinations on top and the utility cluster at the bottom`() {
        val all = listOf("files", "search", "git", "extensions", "settings", "commands", "projects", "close-project", "ext.tool").map {
            NavItem(it, it, IconRef("x"), NavTarget.Container("c"), 1, ScopeFilter.WORKSPACE)
        }
        val (top, cluster) = NavRules.railGroups(all)
        assertEquals(listOf("files", "search", "git", "ext.tool"), ids(top))
        assertEquals(listOf("extensions", "settings", "commands", "projects", "close-project"), ids(cluster))
    }

    @Test fun `a bar never holds more than five cells or fewer than one`() {
        assertEquals(5, NavRules.barCapacity(393f, 44f))
        assertEquals(3, NavRules.barCapacity(140f, 44f))
        assertEquals(1, NavRules.barCapacity(20f, 44f))
    }

    @Test fun `a rail holds as many cells as its height allows`() {
        assertEquals(6, NavRules.railCapacity(340f, 56f))
        assertEquals(1, NavRules.railCapacity(10f, 56f))
    }

    @Test fun `items that fit are all shown and More is absent`() {
        val cells = NavRules.cells(items(5), 5, emptySet(), "i1")
        assertEquals(listOf("i1", "i2", "i3", "i4", "i5"), ids(cells.shown))
        assertFalse(cells.hasMore)
    }

    @Test fun `overflow gives one cell to More so the surface stays within capacity`() {
        val cells = NavRules.cells(items(7), 5, emptySet(), "i1")
        assertEquals(listOf("i1", "i2", "i3", "i4"), ids(cells.shown))
        assertEquals(listOf("i5", "i6", "i7"), ids(cells.more))
        assertFalse(cells.moreActive)
    }

    @Test fun `a pinned item keeps its cell over an earlier unpinned one`() {
        val cells = NavRules.cells(items(7), 5, setOf("i7"), "i1")
        assertEquals(listOf("i1", "i2", "i3", "i7"), ids(cells.shown))
        assertEquals(listOf("i4", "i5", "i6"), ids(cells.more))
    }

    @Test fun `More is marked active when the selected item is inside it`() {
        assertTrue(NavRules.cells(items(7), 5, emptySet(), "i6").moreActive)
        assertFalse(NavRules.cells(items(7), 5, emptySet(), null).moreActive)
    }

    @Test fun `a one cell surface still shows an item and sends the rest to More`() {
        val cells = NavRules.cells(items(3), 1, emptySet(), "i1")
        assertEquals(listOf("i1"), ids(cells.shown))
        assertEquals(listOf("i2", "i3"), ids(cells.more))
    }

    @Test fun `no items gives an empty surface`() {
        val cells = NavRules.cells(emptyList(), 5, emptySet(), null)
        assertTrue(cells.shown.isEmpty())
        assertFalse(cells.hasMore)
    }

    @Test fun `badge counts read plainly up to 99 then cap`() {
        assertNull(NavRules.badgeText(0))
        assertNull(NavRules.badgeText(-3))
        assertEquals("7", NavRules.badgeText(7))
        assertEquals("99", NavRules.badgeText(99))
        assertEquals("99+", NavRules.badgeText(100))
    }

    @Test fun `stored ids are the documented spellings`() {
        assertEquals(listOf("auto", "left", "right", "bottom"), NavPosition.entries.map { it.id })
        assertEquals(listOf("auto", "always", "never"), NavLabels.entries.map { it.id })
    }
}
