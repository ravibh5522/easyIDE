package dev.easyide.sandbox.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RecentFilesTest {

    @get:Rule val temp = TemporaryFolder()

    private fun file(path: String, modified: Long): File =
        File(temp.root, path).apply {
            parentFile.mkdirs()
            writeText("x")
            setLastModified(modified)
        }

    @Test fun `newest first, limited`() {
        file("a.txt", 1_000_000)
        file("src/b.kt", 3_000_000)
        file("c.md", 2_000_000)

        val recent = RecentFiles.scan(temp.root, limit = 2)

        assertEquals(listOf("src/b.kt", "c.md"), recent.map { it.relativePath })
        assertEquals(3_000_000, recent.first().lastModifiedEpochMs)
    }

    @Test fun `ties are ordered by path`() {
        file("b.txt", 5_000_000)
        file("a.txt", 5_000_000)

        assertEquals(listOf("a.txt", "b.txt"), RecentFiles.scan(temp.root, limit = 5).map { it.relativePath })
    }

    @Test fun `dependency, build and dot directories are skipped`() {
        file("node_modules/pkg/index.js", 9_000_000)
        file("build/out.o", 9_000_000)
        file(".git/HEAD", 9_000_000)
        file("main.py", 1_000_000)

        assertEquals(listOf("main.py"), RecentFiles.scan(temp.root, limit = 5).map { it.relativePath })
    }

    @Test fun `symlinks are not followed or listed`() {
        val outside = temp.newFolder("elsewhere").also { File(it, "secret.txt").writeText("s") }
        val project = temp.newFolder("project")
        Files.createSymbolicLink(File(project, "link").toPath(), outside.toPath())
        File(project, "real.txt").writeText("r")

        assertEquals(listOf("real.txt"), RecentFiles.scan(project, limit = 5).map { it.relativePath })
    }

    @Test fun `missing or empty root gives nothing`() {
        assertTrue(RecentFiles.scan(File(temp.root, "nope"), limit = 5).isEmpty())
        assertTrue(RecentFiles.scan(temp.root, limit = 5).isEmpty())
    }
}
