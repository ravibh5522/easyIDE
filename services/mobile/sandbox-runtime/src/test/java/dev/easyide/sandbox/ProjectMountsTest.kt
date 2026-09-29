package dev.easyide.sandbox

import dev.easyide.sandbox.model.ProjectRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ProjectMountsTest {

    private fun p(id: String, name: String, env: String = "e1", created: Long = 0) =
        ProjectRecord(id, name, env, created, created, null)

    private val paths = SandboxPaths(File("/data/files"))

    @Test fun `names become safe folder names and clashes get a suffix, oldest keeps the plain name`() {
        val names = ProjectMounts.folderNames(
            listOf(p("b", "My App", created = 2), p("a", "my-app", created = 1), p("c", "..", created = 3), p("d", "  ", created = 4)),
        )
        assertEquals("my-app", names["a"])
        assertEquals("My-App-2", names["b"])
        assertEquals("project", names["c"])
        assertEquals("project-2", names["d"])
    }

    @Test fun `adding a project never renames an existing one`() {
        val before = ProjectMounts.folderNames(listOf(p("a", "x", created = 1)))
        val after = ProjectMounts.folderNames(listOf(p("a", "x", created = 1), p("b", "x", created = 2)))
        assertEquals(before["a"], after["a"])
    }

    @Test fun `a terminal starts in its own project and sees the environment's others`() {
        val all = listOf(p("a", "api"), p("b", "web"), p("z", "elsewhere", env = "e2"))
        val m = ProjectMounts.resolve(paths, "e1", paths.projectDir("a"), all)
        assertEquals("/projects/api", m.current)
        assertEquals(listOf("/projects/web"), m.siblings.map { it.guestPath })
        assertEquals(paths.projectDir("b"), m.siblings.single().host)
    }

    @Test fun `a project missing from the list falls back to its directory name`() {
        assertEquals("/projects/zzz", ProjectMounts.resolve(paths, "e1", paths.projectDir("zzz"), emptyList()).current)
    }
}
