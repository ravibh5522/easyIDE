package dev.easyide.app.ui.screens.workspace.files

import dev.easyide.sandbox.files.FileNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileIndexerTest {

    private fun file(path: String) = FileNode(path.substringAfterLast('/'), path, isDirectory = false, sizeBytes = 1)
    private fun dir(path: String) = FileNode(path.substringAfterLast('/'), path, isDirectory = true, sizeBytes = 0)

    /** A project as directory -> children, with .gitignore contents by path. */
    private class Project(val dirs: Map<String, List<FileNode>>, val texts: Map<String, String> = emptyMap()) {
        val listed = mutableListOf<String>()
        val indexer = FileIndexer(
            list = { d -> listed += d; dirs[d] },
            ignoreLoader = IgnoreLoader { path -> texts[path] },
        )
    }

    private fun sample(texts: Map<String, String> = emptyMap()) = Project(
        dirs = mapOf(
            "" to listOf(dir("src"), dir(".git"), dir("node_modules"), dir(".github"), file("README.md"), file(".env"), file(".gitignore")),
            "src" to listOf(file("src/a.kt"), file("src/b.log"), dir("src/deep")),
            "src/deep" to listOf(file("src/deep/c.kt")),
            ".git" to listOf(file(".git/HEAD")),
            "node_modules" to listOf(file("node_modules/x.js")),
            ".github" to listOf(file(".github/ci.yml")),
        ),
        texts = texts,
    )

    @Test
    fun `lists every file and skips the git directory`() = runBlocking {
        val p = sample()
        val index = p.indexer.build(hideHidden = false)
        assertEquals(
            setOf("README.md", ".env", ".gitignore", "src/a.kt", "src/b.log", "src/deep/c.kt", "node_modules/x.js", ".github/ci.yml"),
            index.paths.toSet(),
        )
        assertFalse(index.truncated)
        assertFalse(".git" in p.listed)
    }

    @Test
    fun `honours gitignore without entering ignored directories`() = runBlocking {
        val p = sample(mapOf(".gitignore" to "node_modules/\n*.log\n"))
        val index = p.indexer.build(hideHidden = false)
        assertFalse(index.paths.any { it.startsWith("node_modules") })
        assertFalse("src/b.log" in index.paths)
        assertTrue("src/a.kt" in index.paths)
        assertFalse("node_modules" in p.listed)
    }

    @Test
    fun `a nested gitignore applies from its own directory`() = runBlocking {
        val p = Project(
            dirs = mapOf(
                "" to listOf(dir("src"), file("top.tmp")),
                "src" to listOf(file("src/.gitignore"), file("src/x.tmp"), file("src/y.kt")),
            ),
            texts = mapOf("src/.gitignore" to "*.tmp\n"),
        )
        val index = p.indexer.build(hideHidden = false)
        assertEquals(setOf("top.tmp", "src/.gitignore", "src/y.kt"), index.paths.toSet())
    }

    @Test
    fun `hides dotfiles and dot directories on request`() = runBlocking {
        val index = sample().indexer.build(hideHidden = true)
        assertFalse(index.paths.any { it.split('/').any { part -> part.startsWith(".") } })
        assertTrue("README.md" in index.paths)
    }

    @Test
    fun `a directory that cannot be listed is skipped`() = runBlocking {
        val p = Project(dirs = mapOf("" to listOf(dir("gone"), file("a.kt"))))
        assertEquals(listOf("a.kt"), p.indexer.build(false).paths)
    }

    @Test
    fun `a directory loop stops at the depth limit`() = runBlocking {
        val indexer = FileIndexer(
            list = { d -> listOf(dir(if (d.isEmpty()) "a" else "$d/a"), file(if (d.isEmpty()) "f" else "$d/f")) },
            ignoreLoader = IgnoreLoader { null },
        )
        val index = indexer.build(false)
        assertEquals(FileIndexer.MAX_DEPTH + 1, index.paths.size)
    }

    @Test
    fun `tree filter combines the dotfile and gitignore switches`() {
        val ignore = IgnoreIndex.EMPTY.with("", GitignoreRules.parse("*.log"))
        val log = file("a.log")
        val dot = file(".env")
        val plain = file("a.kt")
        assertTrue(TreeFilter.SHOW_ALL.shows(log))
        assertFalse(TreeFilter(respectIgnore = true, ignore = ignore).shows(log))
        assertTrue(TreeFilter(respectIgnore = false, ignore = ignore).shows(log))
        assertFalse(TreeFilter(hideHidden = true).shows(dot))
        assertTrue(TreeFilter(hideHidden = true).shows(plain))
    }

    @Test
    fun `ignore loader re-reads, drops and keeps rules`() = runBlocking {
        val texts = mutableMapOf("d/.gitignore" to "*.a")
        val loader = IgnoreLoader { texts[it] }
        var index = loader.refresh(IgnoreIndex.EMPTY, "d", listOf(file("d/.gitignore")))
        assertTrue(index.isIgnored("d/x.a", false))
        texts["d/.gitignore"] = "*.b"
        index = loader.refresh(index, "d", listOf(file("d/.gitignore")))
        assertFalse(index.isIgnored("d/x.a", false))
        assertTrue(index.isIgnored("d/x.b", false))
        index = loader.refresh(index, "d", listOf(file("d/other")))
        assertFalse(index.isIgnored("d/x.b", false))
    }
}
