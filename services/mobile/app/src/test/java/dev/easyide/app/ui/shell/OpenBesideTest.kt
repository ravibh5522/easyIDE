package dev.easyide.app.ui.shell

import dev.easyide.app.ui.shell.diff.Comparison
import dev.easyide.app.ui.shell.host.AppDocuments
import dev.easyide.app.ui.shell.workspace.forWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Open beside per size class: the rules are decided from the stage and must agree with what the engine then does. */
class OpenBesideTest {
    private val docs = AppDocuments.registries { "t" }.forWorkspace().documents
    private val a = file("a.kt")
    private val b = file("b.kt")
    private val diff = requireNotNull(Comparison.unstaged("a.kt").uri)
    private val extensions = AppDocuments.extensionPage("easyide.python")
    private val settings = AppDocuments.settingsPage("editor")

    private fun stateIn(window: dev.easyide.app.ui.foundation.WindowSize) = workspaceShell(window)

    private fun beside(state: ShellState, uri: DocumentUri): ShellState =
        ShellReducer.reduce(state, ShellAction.Open(uri, OpenOptions(group = GroupTarget.BESIDE)), ShellEnv(docs::resolve))

    private fun ShellState.opened(uri: DocumentUri) = ShellReducer.reduce(this, ShellAction.Open(uri), ShellEnv(docs::resolve))

    private fun result(state: ShellState, uri: DocumentUri) =
        OpenBeside.of(state.current.stage, state.groupCapacity, docs.resolve(uri).supportsSplit)

    @Test fun `a phone never splits and says so`() {
        val s = stateIn(COMPACT).opened(a)
        assertEquals(BesideResult.ONE_GROUP, result(s, diff))
        val after = beside(s, diff)
        assertEquals(1, after.current.stage.groups.size)
        assertEquals(listOf(a, diff), after.current.stage.documents)
        assertEquals(diff, after.current.stage.activeGroup.active)
    }

    @Test fun `a medium window splits once and then reuses the second group`() {
        val s = stateIn(MEDIUM).opened(a)
        assertEquals(BesideResult.NEW_GROUP, result(s, diff))
        val split = beside(s, diff)
        assertEquals(2, split.current.stage.groups.size)
        assertEquals(1, split.current.stage.active)
        // Back on the first group, "beside" is the group that exists.
        val first = split.act(ShellAction.FocusGroup(0))
        assertEquals(BesideResult.NEXT_GROUP, result(first, b))
        assertEquals(2, beside(first, b).current.stage.groups.size)
        assertEquals(b, beside(first, b).current.stage.groups[1].active)
    }

    @Test fun `from the last group of a full medium window it opens where it is`() {
        val full = beside(stateIn(MEDIUM).opened(a), diff)
        assertEquals(BesideResult.NO_ROOM, result(full, b))
        val after = beside(full, b)
        assertEquals(2, after.current.stage.groups.size)
        assertEquals(listOf(b), after.current.stage.groups[1].tabs.map { it.uri }.filter { it == b })
    }

    @Test fun `an expanded window grows to four groups`() {
        var s = stateIn(EXPANDED).opened(a)
        val added = listOf(diff, extensions, settings)
        for ((i, uri) in added.withIndex()) {
            assertEquals(BesideResult.NEW_GROUP, result(s, uri))
            s = beside(s, uri)
            assertEquals(i + 2, s.current.stage.groups.size)
        }
        assertEquals(BesideResult.NO_ROOM, result(s, b))
    }

    @Test fun `book and tabletop hold two groups`() {
        for (window in listOf(BOOK, TABLETOP)) {
            val s = beside(stateIn(window).opened(a), diff)
            assertEquals(2, s.current.stage.groups.size)
            assertEquals(BesideResult.NO_ROOM, result(s, b))
        }
    }

    @Test fun `a file and its diff sit in two groups on a tablet`() {
        val s = beside(stateIn(EXPANDED).opened(a), diff)
        assertEquals(listOf(listOf("a.kt"), listOf("a.kt")), s.tabNames())
        assertEquals(a, s.current.stage.groups[0].active)
        assertEquals(diff, s.current.stage.groups[1].active)
        assertEquals(1, s.current.stage.active)
    }

    @Test fun `an extension page and a settings page open beside a file`() {
        for (page in listOf(extensions, settings)) {
            val s = beside(stateIn(MEDIUM).opened(a), page)
            assertEquals(2, s.current.stage.groups.size)
            assertEquals(page, s.current.stage.groups[1].active)
            assertEquals(a, s.current.stage.groups[0].active)
        }
    }

    @Test fun `a project page is the one app page that stays out of a split`() {
        val project = AppDocuments.projectPage("p1")
        assertFalse(docs.resolve(project).supportsSplit)
        assertTrue(docs.resolve(extensions).supportsSplit)
        val s = stateIn(EXPANDED).opened(a)
        assertEquals(BesideResult.NOT_SPLITTABLE, result(s, project))
        assertEquals(1, beside(s, project).current.stage.groups.size)
    }

    @Test fun `a document already open elsewhere moves beside instead of duplicating`() {
        val s = beside(stateIn(EXPANDED).opened(a).opened(diff), a)
        assertEquals(2, s.current.stage.groups.size)
        assertEquals(1, s.current.stage.documents.count { it == a })
        assertEquals(a, s.current.stage.groups[1].active)
    }

    @Test fun `the rules and the engine agree on which group the document lands in`() {
        for (window in listOf(COMPACT, MEDIUM, EXPANDED, BOOK, TABLETOP)) {
            var s = stateIn(window).opened(a)
            for (uri in listOf(diff, extensions, settings, b)) {
                val expectedNew = result(s, uri) == BesideResult.NEW_GROUP
                val before = s.current.stage.groups.size
                s = beside(s, uri)
                assertEquals("$window $uri", before + if (expectedNew) 1 else 0, s.current.stage.groups.size)
            }
        }
    }

    @Test fun `moving to the next group needs a next group and a document`() {
        assertFalse(OpenBeside.canMoveToNext(EditorStage()))
        val two = beside(stateIn(MEDIUM).opened(a), diff).current.stage
        assertFalse("the active group is the last", OpenBeside.canMoveToNext(two))
        assertTrue(OpenBeside.canMoveToNext(two.focusGroup(0)))
    }
}
