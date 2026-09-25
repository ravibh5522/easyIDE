package dev.easyide.app.ui.shell

import dev.easyide.app.ui.screens.workspace.layout.CloseScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class EditorGroupTest {
    private val a = file("a.kt")
    private val b = file("b.kt")
    private val c = file("c.kt")
    private val d = file("d.kt")

    private fun EditorGroup.names() = tabs.map { it.uri.name }
    private fun EditorGroup.states() = tabs.map { it.state }

    @Test
    fun `opening tabs appends them and activates the newest`() {
        val g = group("a.kt", "b.kt", "c.kt")
        assertEquals(listOf("a.kt", "b.kt", "c.kt"), g.names())
        assertEquals(c, g.active)
        assertEquals(listOf(c, b, a), g.mru)
    }

    @Test
    fun `opening an open document focuses it without duplicating`() {
        val g = group("a.kt", "b.kt").open(a, preview = false)
        assertEquals(2, g.tabs.size)
        assertEquals(a, g.active)
        assertEquals(listOf(a, b), g.mru)
    }

    @Test
    fun `the next preview replaces the current one in place`() {
        val g = group("x.kt").open(file("p1.kt"), preview = true).open(d, preview = false).open(file("p2.kt"), preview = true)
        assertEquals(listOf("x.kt", "p2.kt", "d.kt"), g.names())
        assertEquals(listOf(TabState.KEPT, TabState.PREVIEW, TabState.KEPT), g.states())
        assertEquals(file("p2.kt"), g.active)
        assertEquals(setOf(file("x.kt"), file("p2.kt"), d), g.mru.toSet())
    }

    @Test
    fun `a replaced preview leaves no trace in history and back returns to the previous tab`() {
        val g = group("a.kt").open(file("p1.kt"), preview = true).open(file("p2.kt"), preview = true)
        assertEquals(listOf(a), g.history.back)
        assertEquals(a, g.goBack()?.active)
    }

    @Test
    fun `opening a preview as a normal tab keeps it`() {
        val g = EditorGroup().open(a, preview = true)
        assertEquals(TabState.PREVIEW, g.tabs.single().state)
        assertEquals(TabState.KEPT, g.open(a, preview = false).tabs.single().state)
        assertEquals(TabState.PREVIEW, g.open(a, preview = true).tabs.single().state)
        assertEquals(TabState.KEPT, g.keep(a).tabs.single().state)
    }

    @Test
    fun `closing the active tab activates the most recently used one`() {
        val g = group("a.kt", "b.kt", "c.kt").activate(a).activate(c)
        assertEquals(listOf(c, a, b), g.mru)
        assertEquals(a, g.close(c).active)
        val closedBackground = g.close(b)
        assertEquals(c, closedBackground.active)
        assertEquals(listOf(c, a), closedBackground.mru)
    }

    @Test
    fun `closing the last tab leaves an empty group`() {
        val g = group("a.kt").close(a)
        assertEquals(EditorGroup(), g)
        assertNull(g.active)
        assertSame(g, g.close(a))
    }

    @Test
    fun `pinning moves a tab to the pinned prefix and unpinning to just after it`() {
        val g = group("a.kt", "b.kt", "c.kt")
        val pinB = g.pin(b)
        assertEquals(listOf("b.kt", "a.kt", "c.kt"), pinB.names())
        val pinBoth = pinB.pin(c)
        assertEquals(listOf("b.kt", "c.kt", "a.kt"), pinBoth.names())
        assertEquals(listOf(TabState.PINNED, TabState.PINNED, TabState.KEPT), pinBoth.states())
        assertEquals(listOf("c.kt", "b.kt", "a.kt"), pinBoth.unpin(b).names())
        assertSame(pinBoth, pinBoth.pin(b))
        assertSame(g, g.unpin(a))
    }

    @Test
    fun `pinning a preview tab pins it and frees the preview slot`() {
        val g = EditorGroup().open(a, preview = true).pin(a).open(b, preview = true)
        assertEquals(listOf(TabState.PINNED, TabState.PREVIEW), g.states())
    }

    @Test
    fun `bulk closes leave pinned tabs and this closes even a pinned one`() {
        val g = group("a.kt", "b.kt", "c.kt", "d.kt").pin(a)
        val cases = listOf(
            "others" to (CloseScope.OTHERS to listOf("a.kt", "c.kt")),
            "to the right" to (CloseScope.TO_THE_RIGHT to listOf("a.kt", "b.kt", "c.kt")),
            "all" to (CloseScope.ALL to listOf("a.kt")),
        )
        cases.forEach { (name, case) ->
            val (scope, expected) = case
            assertEquals(name, expected, g.close(c, scope).names())
        }
        assertEquals(listOf("b.kt", "c.kt", "d.kt"), g.close(a, CloseScope.THIS).names())
        assertSame(g, g.close(file("zzz.kt"), CloseScope.ALL))
    }

    @Test
    fun `reorder stays inside the pinned or unpinned region`() {
        val g = group("a.kt", "b.kt", "c.kt", "p.kt").pin(file("p.kt"))
        assertEquals(listOf("p.kt", "a.kt", "b.kt", "c.kt"), g.names())
        assertEquals(g.names(), g.reorder(a, 0).names())
        assertEquals(listOf("p.kt", "b.kt", "c.kt", "a.kt"), g.reorder(a, 99).names())
        assertEquals(listOf("p.kt", "b.kt", "a.kt", "c.kt"), g.reorder(b, 1).names())
        assertEquals(g.names(), g.reorder(file("p.kt"), 3).names())
        assertEquals(g.names(), g.reorder(file("zzz.kt"), 1).names())
    }

    @Test
    fun `a sub page change navigates the same tab and is a history step`() {
        val settings = uri("easyide://settings/editor")
        val fonts = settings.withFragment("fonts")
        val g = EditorGroup().open(settings, preview = true).open(fonts, preview = true)
        assertEquals(1, g.tabs.size)
        assertEquals(fonts, g.tabs.single().uri)
        assertEquals(listOf(settings), g.history.back)
        val back = requireNotNull(g.goBack())
        assertEquals(settings, back.tabs.single().uri)
        assertEquals(listOf(fonts), back.history.forward)
        assertEquals(fonts, back.goForward()?.tabs?.single()?.uri)
    }

    @Test
    fun `history walks back and forward across tabs and stops at the ends`() {
        val g = group("a.kt", "b.kt", "c.kt")
        val atB = requireNotNull(g.goBack())
        assertEquals(b, atB.active)
        val atA = requireNotNull(atB.goBack())
        assertEquals(a, atA.active)
        assertNull(atA.goBack())
        assertEquals(b, atA.goForward()?.active)
        assertEquals(c, atA.goForward()?.goForward()?.active)
        assertNull(g.goForward())
        assertNull(EditorGroup().goBack())
    }

    @Test
    fun `a new location after going back discards forward history`() {
        val g = requireNotNull(group("a.kt", "b.kt", "c.kt").goBack()).open(d, preview = false)
        assertEquals(emptyList<DocumentUri>(), g.history.forward)
    }

    @Test
    fun `closing a tab removes it from history so back skips it`() {
        val g = group("a.kt", "b.kt", "c.kt").close(b)
        assertEquals(listOf(a), g.history.back)
        assertEquals(a, g.goBack()?.active)
    }

    @Test
    fun `history is bounded`() {
        val g = (1..ShellLimits.HISTORY_LIMIT + 20).fold(EditorGroup()) { acc, i -> acc.open(file("f$i.kt"), preview = false) }
        assertEquals(ShellLimits.HISTORY_LIMIT, g.history.back.size)
        assertEquals(file("f${ShellLimits.HISTORY_LIMIT + 19}.kt"), g.history.back.last())
    }

    @Test
    fun `opening without focus keeps the active tab and adds the tab as least recent`() {
        val g = group("a.kt", "b.kt").open(c, preview = false, focus = false)
        assertEquals(b, g.active)
        assertEquals(listOf(b, a, c), g.mru)
        assertEquals(listOf(a), g.history.back)
        val empty = EditorGroup().open(a, preview = false, focus = false)
        assertEquals(a, empty.active)
        assertEquals(NavHistory.EMPTY, empty.history)
    }

    @Test
    fun `inserting a second preview demotes it and inserting a present document only focuses it`() {
        val g = EditorGroup().open(a, preview = true)
        val inserted = g.insert(Tab(b, TabState.PREVIEW), focus = true)
        assertEquals(listOf(TabState.PREVIEW, TabState.KEPT), inserted.states())
        assertEquals(b, inserted.active)
        assertEquals(1, g.insert(Tab(a, TabState.KEPT), focus = true).tabs.size)
        val pinned = group("x.kt").insert(Tab(b, TabState.PINNED), focus = false)
        assertEquals(listOf("b.kt", "x.kt"), pinned.names())
    }

    @Test
    fun `absorb appends the other tabs as least recent and can take its active tab`() {
        val target = group("a.kt", "b.kt")
        val other = group("b.kt", "c.kt", "d.kt").activate(c)
        val kept = target.absorb(other, takeActive = false)
        assertEquals(listOf("a.kt", "b.kt", "c.kt", "d.kt"), kept.names())
        assertEquals(b, kept.active)
        assertEquals(listOf(b, a, c, d), kept.mru)
        assertEquals(c, target.absorb(other, takeActive = true).active)
        assertEquals(c, EditorGroup().absorb(other, takeActive = true).active)
    }

    @Test
    fun `take detaches a tab and prunes its history`() {
        val (tab, rest) = requireNotNull(group("a.kt", "b.kt").take(a))
        assertEquals(a, tab.key)
        assertEquals(listOf("b.kt"), rest.names())
        assertEquals(NavHistory.EMPTY, rest.history)
        assertNull(rest.take(a))
    }

    @Test
    fun `invalid groups cannot be constructed`() {
        val ta = Tab(a)
        val cases = listOf<Pair<String, () -> Unit>>(
            "duplicate" to { EditorGroup(listOf(ta, ta), a, listOf(a, a)) },
            "two previews" to { EditorGroup(listOf(Tab(a, TabState.PREVIEW), Tab(b, TabState.PREVIEW)), a, listOf(a, b)) },
            "pinned after normal" to { EditorGroup(listOf(ta, Tab(b, TabState.PINNED)), a, listOf(a, b)) },
            "active not a tab" to { EditorGroup(listOf(ta), b, listOf(a)) },
            "tabs without active" to { EditorGroup(listOf(ta), null, listOf(a)) },
            "active without tabs" to { EditorGroup(emptyList(), a, emptyList()) },
            "mru missing a tab" to { EditorGroup(listOf(ta, Tab(b)), a, listOf(a)) },
        )
        cases.forEach { (name, build) -> assertThrows(name, IllegalArgumentException::class.java) { build() } }
    }

    @Test
    fun `sanitized repairs untrusted parts`() {
        val g = EditorGroup.sanitized(
            tabs = listOf(Tab(a), Tab(a, TabState.PINNED), Tab(b, TabState.PREVIEW), Tab(c, TabState.PREVIEW), Tab(d, TabState.PINNED)),
            active = file("gone.kt"),
            mru = listOf(file("gone.kt"), c, c, a),
        )
        assertEquals(listOf("d.kt", "a.kt", "b.kt", "c.kt"), g.names())
        assertEquals(listOf(TabState.PINNED, TabState.KEPT, TabState.PREVIEW, TabState.KEPT), g.states())
        assertEquals(listOf(c, a, d, b), g.mru)
        assertEquals(c, g.active)
        assertEquals(EditorGroup(), EditorGroup.sanitized(emptyList(), a, listOf(a)))
    }
}
