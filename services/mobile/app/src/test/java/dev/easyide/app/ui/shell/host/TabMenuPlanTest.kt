package dev.easyide.app.ui.shell.host

import dev.easyide.app.ui.screens.workspace.layout.CloseScope
import dev.easyide.app.ui.shell.EditorGroup
import dev.easyide.app.ui.shell.file
import dev.easyide.app.ui.shell.group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabMenuPlanTest {
    private val wide = TabMenuContext(canBeside = true, canMoveNext = true)
    private val phone = TabMenuContext(canBeside = false, canMoveNext = false)
    private fun plan(g: EditorGroup, name: String, context: TabMenuContext) = TabMenuPlan.of(g, file(name), context)

    @Test fun `a middle tab offers everything on a wide window`() {
        val g = group("a", "b", "c")
        assertEquals(
            listOf(
                listOf(TabAction.OPEN_BESIDE, TabAction.MOVE_NEXT),
                listOf(TabAction.PIN),
                listOf(TabAction.CLOSE, TabAction.CLOSE_OTHERS, TabAction.CLOSE_RIGHT, TabAction.CLOSE_ALL),
            ),
            plan(g, "b", wide),
        )
    }

    @Test fun `the phone menu has no stage entries`() {
        val sections = plan(group("a", "b"), "a", phone)
        assertEquals(listOf(listOf(TabAction.PIN), listOf(TabAction.CLOSE, TabAction.CLOSE_OTHERS, TabAction.CLOSE_RIGHT, TabAction.CLOSE_ALL)), sections)
    }

    @Test fun `entries that would do nothing are left out`() {
        val only = plan(group("a"), "a", phone).flatten()
        assertTrue(TabAction.CLOSE_OTHERS !in only)
        assertTrue(TabAction.CLOSE_RIGHT !in only)
        val last = plan(group("a", "b"), "b", phone).flatten()
        assertTrue(TabAction.CLOSE_RIGHT !in last)
        assertTrue(TabAction.CLOSE_OTHERS in last)
    }

    @Test fun `the state entries follow the tab state`() {
        val preview = EditorGroup().open(file("p"), preview = true)
        assertEquals(listOf(TabAction.KEEP, TabAction.PIN), plan(preview, "p", phone)[0])
        val pinned = group("a").pin(file("a"))
        assertEquals(listOf(TabAction.UNPIN), plan(pinned, "a", phone)[0])
    }

    @Test fun `an unknown tab has no menu`() {
        assertTrue(plan(group("a"), "zzz", wide).isEmpty())
    }

    @Test fun `closing others names every other tab but leaves pinned ones`() {
        val g = group("a", "b", "c", "d").pin(file("d"))
        assertEquals(listOf(file("b"), file("c")), g.closing(file("a"), CloseScope.OTHERS).filter { it != file("d") })
        assertTrue(file("d") !in g.closing(file("a"), CloseScope.OTHERS))
        assertEquals(listOf(file("b")), g.closing(file("b"), CloseScope.THIS))
        assertEquals(listOf(file("c")), g.closing(file("b"), CloseScope.TO_THE_RIGHT))
        assertEquals(g.close(file("a"), CloseScope.OTHERS).keys, listOf(file("d"), file("a")))
    }
}
