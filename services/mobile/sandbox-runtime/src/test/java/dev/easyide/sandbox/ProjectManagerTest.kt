package dev.easyide.sandbox

import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import dev.easyide.sandbox.external.ExternalFolderSync
import dev.easyide.sandbox.files.ProjectFiles
import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.ProjectRecord
import dev.easyide.sandbox.model.SandboxBackend
import dev.easyide.sandbox.model.SandboxEnvironment
import dev.easyide.sandbox.store.PersistedState
import dev.easyide.sandbox.store.SandboxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ProjectManagerTest {

    @get:Rule val temp = TemporaryFolder()

    private val state = MutableStateFlow(PersistedState())
    private val dataStore = object : DataStore<PersistedState> {
        override val data: Flow<PersistedState> = state
        override suspend fun updateData(transform: suspend (t: PersistedState) -> PersistedState): PersistedState =
            transform(state.value).also { state.value = it }
    }
    private lateinit var paths: SandboxPaths
    private lateinit var manager: ProjectManager
    private var nextId = 0

    @Before fun setUp() {
        paths = SandboxPaths(temp.newFolder("root"))
        manager = ProjectManager(
            store = SandboxStore(dataStore),
            paths = paths,
            projectFiles = ProjectFiles(paths, Dispatchers.Unconfined),
            externalFolderSync = ExternalFolderSync(ContextWrapper(null), Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            clock = { 1_000L + nextId },
            idGenerator = { "p${nextId++}" },
        )
        state.value = PersistedState(
            environments = listOf(
                SandboxEnvironment("env", "Ubuntu", SandboxBackend.PROOT, EnvironmentState.READY, 0, 0),
            ),
        )
    }

    private fun create(name: String): ProjectRecord = runBlocking { manager.create(name, "env").getOrThrow() }

    private fun names(): List<String> = state.value.projects.map { it.name }

    @Test fun `create without starter files leaves the directory empty`() = runBlocking {
        val project = manager.create("cloned", "env", seedStarterFiles = false).getOrThrow()

        assertTrue(paths.projectDir(project.id).isDirectory)
        assertEquals(0, paths.projectDir(project.id).list()!!.size)
    }

    @Test fun `rename changes only the name`() {
        val project = create("old")

        val renamed = runBlocking { manager.rename(project.id, "  new  ").getOrThrow() }

        assertEquals("new", renamed.name)
        assertEquals(project.copy(name = "new"), state.value.project(project.id))
        assertTrue(paths.projectDir(project.id).isDirectory)
    }

    @Test fun `rename to its own name in another case is allowed`() {
        val project = create("notes")

        runBlocking { manager.rename(project.id, "Notes").getOrThrow() }

        assertEquals(listOf("Notes"), names())
    }

    @Test fun `rename rejects a taken name and a blank one`() {
        create("a")
        val b = create("b")

        val taken = runBlocking { manager.rename(b.id, "A") }
        val blank = runBlocking { manager.rename(b.id, " ") }

        assertTrue(taken.exceptionOrNull() is SandboxError.DuplicateName)
        assertTrue(blank.exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf("a", "b"), names())
    }

    @Test fun `rename of an unknown project fails`() {
        val result = runBlocking { manager.rename("ghost", "x") }

        assertTrue(result.exceptionOrNull() is SandboxError.ProjectNotFound)
    }

    @Test fun `duplicate copies files under a new id in the same environment`() {
        val source = create("app")
        File(paths.projectDir(source.id), "sub").mkdirs()
        File(paths.projectDir(source.id), "sub/data.txt").writeText("hello")

        val copy = runBlocking { manager.duplicate(source.id, "app copy").getOrThrow() }

        assertNotEquals(source.id, copy.id)
        assertEquals("env", copy.environmentId)
        assertNull(copy.externalFolderUri)
        assertEquals("hello", File(paths.projectDir(copy.id), "sub/data.txt").readText())
        // Independent: editing the copy leaves the original alone.
        File(paths.projectDir(copy.id), "sub/data.txt").writeText("changed")
        assertEquals("hello", File(paths.projectDir(source.id), "sub/data.txt").readText())
        assertEquals(listOf("app", "app copy"), names())
    }

    @Test fun `duplicate does not copy what a symlink points at`() {
        val source = create("app")
        val outside = temp.newFolder("outside").also { File(it, "big.bin").writeText("x") }
        Files.createSymbolicLink(File(paths.projectDir(source.id), "link").toPath(), outside.toPath())

        val copy = runBlocking { manager.duplicate(source.id, "app 2").getOrThrow() }

        assertTrue(Files.isSymbolicLink(File(paths.projectDir(copy.id), "link").toPath()))
    }

    @Test fun `duplicate rejects a taken name and leaves nothing behind`() {
        val source = create("app")

        val result = runBlocking { manager.duplicate(source.id, "APP") }

        assertTrue(result.exceptionOrNull() is SandboxError.DuplicateName)
        assertEquals(listOf("app"), names())
        assertEquals(listOf(source.id), paths.projectsDir.list()!!.toList())
    }

    @Test fun `delete removes the record and the working copy but never the environment`() {
        val project = create("gone")
        val rootfs = paths.rootfsDir("env").apply { mkdirs() }
        File(rootfs, "marker").writeText("rootfs")

        runBlocking { manager.delete(project.id, deleteFiles = true).getOrThrow() }

        assertNull(state.value.project(project.id))
        assertFalse(paths.projectDir(project.id).exists())
        assertEquals(listOf("env"), state.value.environments.map { it.id })
        assertEquals("rootfs", File(rootfs, "marker").readText())
    }

    @Test fun `delete does not follow a project symlink into an environment`() {
        val project = create("sneaky")
        val rootfs = paths.rootfsDir("env").apply { mkdirs() }
        File(rootfs, "marker").writeText("rootfs")
        Files.createSymbolicLink(File(paths.projectDir(project.id), "to-rootfs").toPath(), rootfs.toPath())

        runBlocking { manager.delete(project.id, deleteFiles = true).getOrThrow() }

        assertEquals("rootfs", File(rootfs, "marker").readText())
    }

    @Test fun `delete without files keeps the directory`() {
        val project = create("keep")

        runBlocking { manager.delete(project.id, deleteFiles = false).getOrThrow() }

        assertNull(state.value.project(project.id))
        assertTrue(paths.projectDir(project.id).isDirectory)
    }

    @Test fun `delete of an unknown project fails`() {
        val result = runBlocking { manager.delete("ghost", deleteFiles = true) }

        assertTrue(result.exceptionOrNull() is SandboxError.ProjectNotFound)
    }
}
