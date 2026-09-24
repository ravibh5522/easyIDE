package dev.easyide.app.ui.screens.workspace.session

import androidx.compose.ui.text.TextRange
import dev.easyide.app.diagnostics.LogLevel
import dev.easyide.app.diagnostics.LogSink
import dev.easyide.app.session.Hashes
import dev.easyide.app.session.SessionStore
import dev.easyide.app.ui.screens.workspace.EditorSelections
import dev.easyide.app.ui.screens.workspace.EditorTab
import dev.easyide.app.ui.screens.workspace.WorkspaceUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionPersistenceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root by lazy { tmp.newFolder("sessions") }
    private val store by lazy { SessionStore(root) }
    private val selections = EditorSelections()
    private val scrolls = EditorScrolls()
    private val logged = mutableListOf<String>()
    private val state = MutableStateFlow(WorkspaceUiState())
    private val persistence by lazy {
        SessionPersistence("p", state, selections, scrolls, { null }, store, Dispatchers.Unconfined, { 42L }, LogSink { level, _, msg -> if (level >= LogLevel.WARN) logged += msg })
    }

    private fun tab(path: String, content: String, saved: String = content) = EditorTab(path, path.substringAfterLast('/'), content, saved)

    private fun show(vararg tabs: EditorTab, active: String? = tabs.firstOrNull()?.relativePath) {
        state.value = WorkspaceUiState(openTabs = tabs.toList(), activeTabPath = active, expandedDirs = setOf("src"))
    }

    private fun save() = runBlocking { persistence.save() }

    private fun backupFile(path: String) = File(root, "p/backups/${SessionStore.backupNameFor(path)}")

    @Test fun `dirty tabs get a backup based on the saved text and clean tabs do not`() {
        show(tab("a.txt", "edited", saved = "orig"), tab("dir/b.txt", "same"))
        selections["a.txt"] = TextRange(2, 4)
        scrolls.record("a.txt", ScrollPos(300, 7))
        save()

        val snap = store.load("p")!!.snapshot
        assertEquals(listOf("a.txt", "dir/b.txt"), snap.tabs.map { it.path })
        assertEquals("a.txt", snap.activePath)
        assertEquals(listOf("src"), snap.expandedDirs)
        val a = snap.tabs[0]
        assertEquals(2, a.caretStart)
        assertEquals(4, a.caretEnd)
        assertEquals(300, a.scrollY)
        assertEquals(7, a.scrollX)
        assertEquals(Hashes.sha256Hex("orig"), a.backup!!.baseSha256)
        assertEquals("edited", store.load("p")!!.backupText(a.backup!!))
        assertNull(snap.tabs[1].backup)
        assertEquals(42L, snap.savedAtMs)
    }

    @Test fun `an unchanged buffer is not rewritten`() {
        show(tab("a.txt", "edited", saved = "orig"))
        save()
        backupFile("a.txt").writeText("tampered")
        save()
        assertEquals("tampered", backupFile("a.txt").readText())
    }

    @Test fun `a changed buffer is rewritten`() {
        show(tab("a.txt", "one", saved = "orig"))
        save()
        show(tab("a.txt", "two", saved = "orig"))
        save()
        assertEquals("two", backupFile("a.txt").readText())
    }

    @Test fun `a backup pruned after the buffer was saved is rewritten if it turns dirty again with the same text`() {
        show(tab("a.txt", "edited", saved = "orig"))
        save()
        show(tab("a.txt", "edited", saved = "edited"))
        save()
        assertFalse(backupFile("a.txt").exists())
        show(tab("a.txt", "edited", saved = "orig"))
        save()
        val snap = store.load("p")!!
        assertEquals("edited", snap.backupText(snap.snapshot.tabs.single().backup!!))
    }

    @Test fun `an empty workspace stores nothing`() {
        save()
        assertNull(store.load("p"))
    }

    @Test fun `discard deletes the stored session and later saves write nothing`() {
        show(tab("a.txt", "edited", saved = "orig"))
        save()
        assertNotNull(store.load("p"))
        persistence.discard()
        assertNull(store.load("p"))
        save()
        assertNull(store.load("p"))
    }

    @Test fun `stop keeps what was stored and writes nothing more`() {
        show(tab("a.txt", "edited", saved = "orig"))
        save()
        persistence.stop()
        show(tab("a.txt", "later", saved = "orig"))
        save()
        val snap = store.load("p")!!
        assertEquals("edited", snap.backupText(snap.snapshot.tabs.single().backup!!))
    }

    @Test fun `an unwritable disk is logged and does not throw`() {
        show(tab("a.txt", "edited", saved = "orig"))
        // A file where the session directory should be makes every write fail with an IOException.
        File(root, "p").writeText("in the way")
        save()
        assertTrue(logged.any { it.contains("could not save the session") })
    }
}
