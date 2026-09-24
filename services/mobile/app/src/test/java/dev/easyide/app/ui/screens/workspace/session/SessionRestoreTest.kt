package dev.easyide.app.ui.screens.workspace.session

import androidx.compose.ui.text.TextRange
import dev.easyide.app.session.BackupRef
import dev.easyide.app.session.ExternalState
import dev.easyide.app.session.Hashes
import dev.easyide.app.session.LayoutSnapshot
import dev.easyide.app.session.SessionSnapshot
import dev.easyide.app.session.SessionStore
import dev.easyide.app.session.TabSnapshot
import dev.easyide.sandbox.SandboxPaths
import dev.easyide.sandbox.files.ProjectFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionRestoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sandboxRoot by lazy { tmp.newFolder("sandbox") }
    private val store by lazy { SessionStore(tmp.newFolder("sessions")) }
    private val restore by lazy { SessionRestore("p", store, ProjectFiles(SandboxPaths(sandboxRoot), Dispatchers.Unconfined), Dispatchers.Unconfined) }

    private fun disk(path: String, text: String) {
        File(sandboxRoot, "projects/p/$path").apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun stored(vararg tabs: TabSnapshot, active: String? = null, dirs: List<String> = emptyList(), backups: Map<String, String> = emptyMap()) {
        store.save(
            SessionSnapshot("p", 1, tabs.toList(), active, dirs, LayoutSnapshot(true, false, true, "SOURCE_CONTROL")),
            backups,
        )
    }

    private fun dirtyTab(path: String, base: String, caret: Int = 0) =
        TabSnapshot(path, caretStart = caret, caretEnd = caret, backup = BackupRef(SessionStore.backupNameFor(path), Hashes.sha256Hex(base)))

    private fun load(restoreTabs: Boolean = true) = runBlocking { restore.load(restoreTabs) }

    @Test fun `nothing stored restores nothing`() {
        assertNull(load())
    }

    @Test fun `clean tabs reopen from disk in order with caret scroll and the active tab`() {
        disk("a.txt", "alpha")
        disk("src/b.kt", "bravo")
        stored(
            TabSnapshot("a.txt", caretStart = 1, caretEnd = 3, scrollY = 120, scrollX = 4),
            TabSnapshot("src/b.kt"),
            active = "src/b.kt", dirs = listOf("src"),
        )
        val r = load()!!
        assertEquals(listOf("a.txt", "src/b.kt"), r.tabs.map { it.tab.relativePath })
        assertEquals("alpha", r.tabs[0].tab.content)
        assertEquals(TextRange(1, 3), r.tabs[0].caret)
        assertEquals(ScrollPos(120, 4), r.tabs[0].scroll)
        assertEquals("src/b.kt", r.activePath)
        assertEquals(setOf("src"), r.expandedDirs)
        assertEquals(LayoutSnapshot(true, false, true, "SOURCE_CONTROL"), r.layout)
        assertEquals(0, r.unsavedCount)
    }

    @Test fun `a file deleted since is skipped and an active tab that was skipped is dropped`() {
        disk("a.txt", "alpha")
        stored(TabSnapshot("a.txt"), TabSnapshot("gone.txt"), active = "gone.txt")
        val r = load()!!
        assertEquals(listOf("a.txt"), r.tabs.map { it.tab.relativePath })
        assertNull(r.activePath)
    }

    @Test fun `unsaved edits come back dirty when the disk still holds their base`() {
        disk("a.txt", "base")
        stored(dirtyTab("a.txt", "base", caret = 2), backups = mapOf(SessionStore.backupNameFor("a.txt") to "my edits"))
        val r = load()!!
        val tab = r.tabs.single().tab
        assertEquals("my edits", tab.content)
        assertEquals("base", tab.savedContent)
        assertEquals(ExternalState.InSync, tab.externalState)
        assertTrue(tab.isDirty)
        assertEquals(1, r.unsavedCount)
        assertEquals(TextRange(2, 2), r.tabs.single().caret)
    }

    @Test fun `unsaved edits over a file changed since the backup restore as a conflict`() {
        disk("a.txt", "someone else")
        stored(dirtyTab("a.txt", "base"), backups = mapOf(SessionStore.backupNameFor("a.txt") to "my edits"))
        val tab = load()!!.tabs.single().tab
        assertEquals("my edits", tab.content)
        assertEquals(ExternalState.Conflict("someone else"), tab.externalState)
    }

    @Test fun `unsaved edits of a deleted file keep their text and are marked gone`() {
        stored(dirtyTab("a.txt", "base"), backups = mapOf(SessionStore.backupNameFor("a.txt") to "my edits"))
        val tab = load()!!.tabs.single().tab
        assertEquals("my edits", tab.content)
        assertEquals("", tab.savedContent)
        assertEquals(ExternalState.Gone, tab.externalState)
        assertTrue(tab.editable)
    }

    @Test fun `unsaved edits already written to disk restore as saved`() {
        disk("a.txt", "my edits")
        stored(dirtyTab("a.txt", "base"), backups = mapOf(SessionStore.backupNameFor("a.txt") to "my edits"))
        val tab = load()!!.tabs.single().tab
        assertEquals(false, tab.isDirty)
        assertEquals(ExternalState.InSync, tab.externalState)
    }

    @Test fun `with restoreOpenTabs off only tabs with unsaved edits come back`() {
        disk("a.txt", "base")
        disk("b.txt", "clean")
        stored(
            dirtyTab("a.txt", "base"), TabSnapshot("b.txt"),
            active = "b.txt", dirs = listOf("src"),
            backups = mapOf(SessionStore.backupNameFor("a.txt") to "my edits"),
        )
        val r = load(restoreTabs = false)!!
        assertEquals(listOf("a.txt"), r.tabs.map { it.tab.relativePath })
        assertNull(r.activePath)
        assertEquals(emptySet<String>(), r.expandedDirs)
        assertEquals(1, r.unsavedCount)
    }

    @Test fun `a backup file that was lost restores the tab from disk`() {
        disk("a.txt", "base")
        stored(dirtyTab("a.txt", "base"), backups = mapOf(SessionStore.backupNameFor("a.txt") to "x"))
        File(tmp.root, "sessions/p/backups").listFiles()!!.forEach { it.delete() }
        val tab = load()!!.tabs.single().tab
        assertEquals("base", tab.content)
        assertEquals(false, tab.isDirty)
    }

    @Test fun `a markdown tab keeps the preview state it was saved with`() {
        disk("README.md", "# hi")
        stored(TabSnapshot("README.md", showPreview = false))
        assertEquals(false, load()!!.tabs.single().tab.showPreview)
    }
}
