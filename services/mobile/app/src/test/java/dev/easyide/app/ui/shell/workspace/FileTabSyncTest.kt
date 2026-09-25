package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.EditorStage
import dev.easyide.app.ui.shell.OpenOptions
import dev.easyide.app.ui.shell.ShellAction
import dev.easyide.app.ui.shell.ShellEnv
import dev.easyide.app.ui.shell.ShellReducer
import dev.easyide.app.ui.shell.ShellState
import dev.easyide.app.ui.shell.ScopeState
import dev.easyide.app.ui.shell.COMPACT
import dev.easyide.app.ui.shell.EXPANDED
import dev.easyide.app.ui.shell.uri
import dev.easyide.app.ui.shell.DocumentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTabSyncTest {
    private val settings = dev.easyide.app.ui.shell.type("easyide.settings", "easyide", supportsSplit = false)
    private val env = ShellEnv(typeOf = { if (it.scheme == "file") FileDocuments.type else settings })

    private fun stateOf(window: dev.easyide.app.ui.foundation.WindowSize = COMPACT): ShellState {
        val base = ShellState(window = window)
        return base.copy(workspace = ScopeState.workspace(base.arrangement))
    }

    /** Applies the plan the way the screen does, and returns the resulting state. */
    private fun sync(state: ShellState, paths: List<String>, active: String?): ShellState =
        FileTabSync.plan(paths, active, state.current.stage).fold(state) { s, a -> ShellReducer.reduce(s, a, env) }

    private fun ShellState.files(): List<String> = current.stage.documents.mapNotNull { FileDocuments.pathOf(it) }
    private fun ShellState.activePath(): String? = FileDocuments.pathOf(current.stage.activeGroup.activeTab?.uri)

    @Test fun `a path maps to a workspace file uri and back`() {
        val u = FileDocuments.uriOf("src/main.kt")!!
        assertEquals("file:///workspace/src/main.kt", u.toString())
        assertEquals("src/main.kt", FileDocuments.pathOf(u))
    }

    @Test fun `names with spaces and dots survive the round trip`() {
        listOf("my notes/a b.md", ".gitignore", "dir.d/x..y", "unicode/über.txt").forEach { path ->
            val u = FileDocuments.uriOf(path)!!
            assertEquals(path, FileDocuments.pathOf(DocumentUri.parse(u.toString())))
        }
    }

    @Test fun `only workspace files are file documents`() {
        assertNull(FileDocuments.pathOf(uri("easyide://settings/editor")))
        assertNull(FileDocuments.pathOf(uri("file:///etc/passwd")))
        assertNull(FileDocuments.pathOf(null))
    }

    @Test fun `the stage opens every tab the view model has and focuses the active one`() {
        val s = sync(stateOf(), listOf("a", "b", "c"), "b")
        assertEquals(setOf("a", "b", "c"), s.files().toSet())
        assertEquals("b", s.activePath())
        assertEquals(3, s.files().size)
    }

    @Test fun `a tab closed in the view model disappears from the stage`() {
        val opened = sync(stateOf(), listOf("a", "b"), "b")
        val closed = sync(opened, listOf("a"), "a")
        assertEquals(listOf("a"), closed.files())
        assertEquals("a", closed.activePath())
    }

    @Test fun `closing the last file leaves an empty stage`() {
        val closed = sync(sync(stateOf(), listOf("a"), "a"), emptyList(), null)
        assertTrue(closed.current.stage.documents.isEmpty())
    }

    @Test fun `other documents are left alone`() {
        val page = ShellAction.Open(uri("easyide://settings/editor"))
        val s = sync(stateOf().let { ShellReducer.reduce(it, page, env) }, listOf("a"), "a")
        assertEquals(2, s.current.stage.documents.size)
        assertEquals("a", s.activePath())
        val gone = sync(s, emptyList(), null)
        assertEquals(listOf("easyide://settings/editor"), gone.current.stage.documents.map { it.toString() })
    }

    @Test fun `an already synchronised stage needs no change beyond refocusing`() {
        val s = sync(stateOf(), listOf("a", "b"), "b")
        val again = FileTabSync.plan(listOf("a", "b"), "b", s.current.stage)
        assertTrue(again.all { it is ShellAction.FocusGroup })
    }

    @Test fun `a file already open in another group is activated there, not duplicated`() {
        val wide = stateOf(EXPANDED)
        val opened = sync(wide, listOf("a", "b"), "a")
        val split = ShellReducer.reduce(opened, ShellAction.Split, env)
        val moved = ShellReducer.reduce(split, ShellAction.Open(FileDocuments.uriOf("b")!!, OpenOptions(group = dev.easyide.app.ui.shell.GroupTarget.ACTIVE)), env)
        val s = sync(moved, listOf("a", "b"), "a")
        assertEquals(2, s.files().size)
        assertEquals("a", s.activePath())
    }

    @Test fun `the file type is not restorable and the workspace registries resolve files to it`() {
        assertEquals(false, FileDocuments.type.restorable)
        val docs = dev.easyide.app.ui.shell.host.AppDocuments.registries { "t" }.forWorkspace().documents
        assertEquals(FileDocuments.TYPE_ID, docs.resolve(FileDocuments.uriOf("a.kt")!!).id)
        assertTrue(docs.resolve(uri("easyide://settings/editor")).id != DocumentType.UNAVAILABLE_ID)
    }
}
