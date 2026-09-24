package dev.easyide.app.ui.shell

import dev.easyide.app.ui.screens.workspace.layout.CloseScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class EditorStageTest {
    private val a = file("a.kt")
    private val b = file("b.kt")
    private val c = file("c.kt")
    private val d = file("d.kt")
    private val beside = OpenOptions(group = GroupTarget.BESIDE)

    private fun EditorStage.open(uri: DocumentUri, options: OpenOptions = OpenOptions(), capacity: Int = 4) =
        open(uri, options, capacity, typeOf(uri))

    private fun EditorStage.names() = groups.map { g -> g.tabs.map { it.uri.name } }

    @Test
    fun `a new stage has one empty group`() {
        val s = EditorStage()
        assertEquals(1, s.groups.size)
        assertEquals(0, s.active)
        assertEquals(emptyList<DocumentUri>(), s.documents)
    }

    @Test
    fun `open in the active group focuses an already open document wherever it is`() {
        val s = EditorStage().open(a).open(b, beside).open(c)
        assertEquals(listOf(listOf("a.kt"), listOf("b.kt", "c.kt")), s.names())
        val focused = s.focusGroup(0).open(c)
        assertEquals(listOf(listOf("a.kt"), listOf("b.kt", "c.kt")), focused.names())
        assertEquals(1, focused.active)
    }

    @Test
    fun `beside creates the split on demand and then reuses the neighbour`() {
        val s = EditorStage().open(a).open(b, beside)
        assertEquals(listOf(listOf("a.kt"), listOf("b.kt")), s.names())
        assertEquals(1, s.active)
        val again = s.focusGroup(0).open(c, beside)
        assertEquals(listOf(listOf("a.kt"), listOf("b.kt", "c.kt")), again.names())
        assertEquals(1, again.active)
    }

    @Test
    fun `beside without room falls back to the active group`() {
        val s = EditorStage().open(a).open(b, beside, capacity = 1)
        assertEquals(listOf(listOf("a.kt", "b.kt")), s.names())
        val full = EditorStage().open(a).open(b, beside, capacity = 2).open(c, beside, capacity = 2)
        assertEquals(listOf(listOf("a.kt"), listOf("b.kt", "c.kt")), full.names())
    }

    @Test
    fun `new group always splits while there is room and otherwise reuses the neighbour`() {
        val s = EditorStage().open(a).open(b, beside).focusGroup(0).open(c, OpenOptions(group = GroupTarget.NEW))
        assertEquals(listOf(listOf("a.kt"), listOf("c.kt"), listOf("b.kt")), s.names())
        assertEquals(1, s.active)
        val capped = EditorStage().open(a).open(b, beside, 2).focusGroup(0).open(c, OpenOptions(group = GroupTarget.NEW), 2)
        assertEquals(listOf(listOf("a.kt"), listOf("b.kt", "c.kt")), capped.names())
    }

    @Test
    fun `there are never more than four groups`() {
        var s = EditorStage()
        repeat(10) { i -> s = s.open(file("f$i.kt"), OpenOptions(group = GroupTarget.NEW), capacity = 9) }
        assertEquals(ShellLimits.MAX_GROUPS, s.groups.size)
        assertEquals(ShellLimits.MAX_GROUPS, EditorStage().split(9).split(9).split(9).split(9).split(9).groups.size)
        assertThrows(IllegalArgumentException::class.java) { EditorStage(List(5) { EditorGroup() }) }
        assertThrows(IllegalArgumentException::class.java) { EditorStage(active = 1) }
    }

    @Test
    fun `opening an open document beside moves it and drops the group it emptied`() {
        val s = EditorStage().open(a).open(a, beside)
        assertEquals(listOf(listOf("a.kt")), s.names())
        val kept = EditorStage().open(a).open(b).open(a, beside)
        assertEquals(listOf(listOf("b.kt"), listOf("a.kt")), kept.names())
        assertEquals(1, kept.active)
    }

    @Test
    fun `a multiple type may appear once per group but never twice in one`() {
        val t = terminal("1")
        val s = EditorStage().open(t).open(t, beside)
        assertEquals(2, s.groups.size)
        assertEquals(listOf(t), s.groups[0].keys)
        assertEquals(listOf(t), s.groups[1].keys)
        assertEquals(s.groups, s.open(t, OpenOptions(group = GroupTarget.ACTIVE)).groups)
        assertEquals(2, s.documents.size)
    }

    @Test
    fun `a type that cannot split opens in the active group`() {
        val diff = requireNotNull(DocumentUri.gitDiff("/workspace/a.kt", "HEAD", "working"))
        val s = EditorStage().open(a).open(diff, beside)
        assertEquals(1, s.groups.size)
        assertEquals(listOf(a, diff), s.groups[0].keys)
    }

    @Test
    fun `background open keeps focus and the active group`() {
        val s = EditorStage().open(a).open(b, beside).focusGroup(0).open(c, OpenOptions(group = GroupTarget.BESIDE, focus = false))
        assertEquals(0, s.active)
        assertEquals(listOf(listOf("a.kt"), listOf("b.kt", "c.kt")), s.names())
        assertEquals(b, s.groups[1].active)
    }

    @Test
    fun `preview open replaces the preview in the target group only`() {
        val p1 = file("p1.kt")
        val p2 = file("p2.kt")
        val s = EditorStage().open(p1, OpenOptions(preview = true)).open(a, beside).open(p2, OpenOptions(preview = true))
        assertEquals(listOf(listOf("p1.kt"), listOf("a.kt", "p2.kt")), s.names())
    }

    @Test
    fun `closing the last tab of a split group removes it and closing the only group leaves it empty`() {
        val s = EditorStage().open(a).open(b, beside)
        val closed = s.close(1, b)
        assertEquals(listOf(listOf("a.kt")), closed.names())
        assertEquals(0, closed.active)
        assertEquals(listOf(emptyList<String>()), closed.close(0, a).names())
        assertEquals(1, closed.close(0, a).groups.size)
    }

    @Test
    fun `removing a group keeps the active group pointing at the same group or its left neighbour`() {
        val three = EditorStage().open(a).open(b, OpenOptions(group = GroupTarget.NEW)).open(c, OpenOptions(group = GroupTarget.NEW))
        assertEquals(3, three.groups.size)
        val active2 = three.focusGroup(2)
        assertEquals(1, active2.close(1, active2.groups[1].keys.single()).active)
        assertEquals(1, active2.close(2, c).active)
        assertEquals(0, three.focusGroup(0).close(1, three.groups[1].keys.single()).active)
    }

    @Test
    fun `close many honours pinned tabs and drops emptied groups`() {
        val s = EditorStage().open(a).open(b).open(c, beside).pin(1, c)
        assertEquals(listOf(listOf("a.kt", "b.kt"), listOf("c.kt")), s.names())
        assertEquals(listOf(listOf("b.kt")), s.close(0, b, CloseScope.OTHERS).close(1, c, CloseScope.THIS).names())
        assertEquals(listOf(listOf("a.kt", "b.kt"), listOf("c.kt")), s.close(1, c, CloseScope.ALL).names())
    }

    @Test
    fun `close where closes matching documents in every group`() {
        val s = EditorStage().open(terminal("1")).open(a).open(terminal("2"), beside).open(terminal("1"), beside)
        val out = s.closeWhere { it.scheme == "terminal" }
        assertEquals(listOf(listOf("a.kt")), out.names())
        assertEquals(1, out.groups.size)
    }

    @Test
    fun `move sends a tab to another group and focuses it there`() {
        val s = EditorStage().open(a).open(b).open(c, beside)
        val moved = s.move(0, a, 1)
        assertEquals(listOf(listOf("b.kt"), listOf("c.kt", "a.kt")), moved.names())
        assertEquals(1, moved.active)
        assertEquals(a, moved.groups[1].active)
        assertSame(s, s.move(0, a, 0))
        assertSame(s, s.move(0, a, 5))
        assertSame(s, s.move(0, file("zzz.kt"), 1))
        val emptied = moved.move(0, b, 1)
        assertEquals(listOf(listOf("c.kt", "a.kt", "b.kt")), emptied.names())
        assertEquals(0, emptied.active)
    }

    @Test
    fun `moving a preview into a group that has one keeps only one preview`() {
        val s = EditorStage().open(a, OpenOptions(preview = true)).open(b, OpenOptions(group = GroupTarget.BESIDE, preview = true))
        val moved = s.move(0, a, 1)
        assertEquals(listOf(TabState.PREVIEW, TabState.KEPT), moved.groups.single().tabs.map { it.state })
    }

    @Test
    fun `split adds an empty group after the active one up to the capacity`() {
        val s = EditorStage().open(a).split(2)
        assertEquals(listOf(listOf("a.kt"), emptyList()), s.names())
        assertEquals(1, s.active)
        assertSame(s, s.split(2))
        assertEquals(1, EditorStage().split(1).groups.size)
        val opened = s.open(b)
        assertEquals(listOf(listOf("a.kt"), listOf("b.kt")), opened.names())
    }

    @Test
    fun `unsplit merges into the neighbour without losing documents`() {
        val s = EditorStage().open(a).open(b, beside).open(c, OpenOptions(group = GroupTarget.NEW))
        assertEquals(listOf(listOf("a.kt"), listOf("b.kt"), listOf("c.kt")), s.names())
        assertEquals(2, s.active)
        val merged = s.unsplit(1)
        assertEquals(listOf(listOf("a.kt", "b.kt"), listOf("c.kt")), merged.names())
        assertEquals(1, merged.active)
        val first = s.unsplit(0)
        assertEquals(listOf(listOf("b.kt", "a.kt"), listOf("c.kt")), first.names())
        val one = s.unsplit(2).unsplit(1)
        assertEquals(1, one.groups.size)
        assertEquals(setOf(a, b, c), one.documents.toSet())
        assertEquals(EditorStage().names(), EditorStage().unsplit(0).names())
        assertSame(s, s.unsplit(9))
    }

    @Test
    fun `fit merges surplus groups and keeps every document`() {
        val s = EditorStage().open(a).open(b, beside).open(c, beside).open(d, OpenOptions(group = GroupTarget.NEW))
        val before = s.documents.toSet()
        listOf(4, 3, 2, 1).forEach { cap ->
            val fitted = s.fit(cap)
            assertEquals(minOf(cap, s.groups.size), fitted.groups.size)
            assertEquals(before, fitted.documents.toSet())
        }
        assertEquals(1, s.fit(0).groups.size)
        assertSame(s, s.fit(9))
    }

    @Test
    fun `fit lands the active group on the group that absorbed it`() {
        val s = EditorStage().open(a).open(b, beside)
        assertEquals(1, s.active)
        val fitted = s.fit(1)
        assertEquals(0, fitted.active)
        assertEquals(b, fitted.activeGroup.active)
    }

    @Test
    fun `arranged shapes the stage to a preset`() {
        val two = EditorStage().open(a).arranged(2, SplitAxis.COLUMN, 4)
        assertEquals(listOf(listOf("a.kt"), emptyList()), two.names())
        assertEquals(SplitAxis.COLUMN, two.axis)
        assertEquals(1, two.arranged(1, SplitAxis.ROW, 4).groups.size)
        assertEquals(2, EditorStage().arranged(4, SplitAxis.ROW, 2).groups.size)
        assertEquals(1, EditorStage().arranged(0, SplitAxis.ROW, 4).groups.size)
    }

    @Test
    fun `stage history steps the active group only`() {
        val s = EditorStage().open(a).open(b).open(c, beside).focusGroup(0)
        assertEquals(a, requireNotNull(s.back()).groups[0].active)
        assertEquals(null, s.focusGroup(1).back())
        assertEquals(null, s.forward())
        assertEquals(NavHistory.EMPTY, s.clearHistory().groups[0].history)
    }
}
