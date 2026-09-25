package dev.easyide.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val store by lazy { SessionStore(tmp.newFolder("sessions")) }

    private fun snapshot(vararg tabs: TabSnapshot, id: String = "p") =
        SessionSnapshot(id, 1, tabs.toList(), null, emptyList(), null)

    private fun backupName(path: String) = SessionStore.backupNameFor(path)

    @Test fun `a saved session loads with its backup text`() {
        val name = backupName("a.txt")
        store.save(snapshot(TabSnapshot("a.txt", backup = BackupRef(name, "base"))), mapOf(name to "unsaved é"))
        val loaded = store.load("p")!!
        assertEquals("a.txt", loaded.snapshot.tabs.single().path)
        assertEquals("unsaved é", loaded.backupText(loaded.snapshot.tabs.single().backup!!))
    }

    @Test fun `no stored session is null`() {
        assertNull(store.load("nothing"))
    }

    @Test fun `a corrupt snapshot file is treated as no session`() {
        store.save(snapshot(TabSnapshot("a")), emptyMap())
        File(tmp.root, "sessions/p/${SessionStore.SNAPSHOT_FILE}").writeText("{ torn")
        assertNull(store.load("p"))
    }

    @Test fun `saving prunes backups the snapshot no longer references`() {
        val a = backupName("a.txt")
        val b = backupName("b.txt")
        store.save(snapshot(TabSnapshot("a.txt", backup = BackupRef(a, "x")), TabSnapshot("b.txt", backup = BackupRef(b, "y"))), mapOf(a to "A", b to "B"))
        store.save(snapshot(TabSnapshot("a.txt", backup = BackupRef(a, "x")), TabSnapshot("b.txt")), emptyMap())
        val backups = File(tmp.root, "sessions/p/backups").list().orEmpty().toSet()
        assertEquals(setOf(a), backups)
    }

    @Test fun `unchanged backups are not rewritten`() {
        val a = backupName("a.txt")
        val snap = snapshot(TabSnapshot("a.txt", backup = BackupRef(a, "x")))
        store.save(snap, mapOf(a to "first"))
        store.save(snap, emptyMap())
        assertEquals("first", store.load("p")!!.backupText(BackupRef(a, "x")))
    }

    @Test fun `an empty snapshot clears the stored session`() {
        store.save(snapshot(TabSnapshot("a")), emptyMap())
        store.save(snapshot(), emptyMap())
        assertNull(store.load("p"))
        assertFalse(File(tmp.root, "sessions/p").exists())
    }

    @Test fun `no temp files are left behind`() {
        val a = backupName("a.txt")
        store.save(snapshot(TabSnapshot("a.txt", backup = BackupRef(a, "x"))), mapOf(a to "A"))
        val names = File(tmp.root, "sessions/p").walkTopDown().map { it.name }.toList()
        assertTrue(names.none { it.endsWith(".tmp") })
    }

    @Test fun `a backup reference cannot escape the backup directory`() {
        store.save(snapshot(TabSnapshot("a")), emptyMap())
        val loaded = store.load("p")!!
        File(tmp.root, "sessions/p/secret.txt").writeText("secret")
        assertNull(loaded.backupText(BackupRef("../secret.txt", "x")))
        assertNull(loaded.backupText(BackupRef("secret.txt", "x")))
        assertThrows(IllegalArgumentException::class.java) { store.save(snapshot(TabSnapshot("a")), mapOf("../evil" to "x")) }
    }

    @Test fun `unsafe project ids are refused`() {
        assertThrows(IllegalArgumentException::class.java) { store.load("..") }
        assertThrows(IllegalArgumentException::class.java) { store.load("a/b") }
        assertThrows(IllegalArgumentException::class.java) { store.clear("") }
    }

    @Test fun `storedIds lists the projects with a session`() {
        store.save(snapshot(TabSnapshot("a"), id = "one"), emptyMap())
        store.save(snapshot(TabSnapshot("a"), id = "two"), emptyMap())
        assertEquals(setOf("one", "two"), store.storedIds())
        store.clear("one")
        assertEquals(setOf("two"), store.storedIds())
    }

    @Test fun `backup names are stable per path and distinct across paths`() {
        assertEquals(backupName("x/y.kt"), backupName("x/y.kt"))
        assertTrue(backupName("x/y.kt") != backupName("x/z.kt"))
        assertTrue(SessionStore.isBackupName(backupName("x/y.kt")))
    }
}
