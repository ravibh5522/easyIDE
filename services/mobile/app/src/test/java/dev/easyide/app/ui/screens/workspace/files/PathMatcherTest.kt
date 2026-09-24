package dev.easyide.app.ui.screens.workspace.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PathMatcherTest {

    private fun rank(query: String, paths: List<String>, recent: List<String> = emptyList(), limit: Int = 10) =
        PathMatcher.rank(query, paths, recent, limit).map { it.path }

    @Test
    fun `a name match beats a match spread over directories`() {
        val paths = listOf("src/editor/panes/x.kt", "src/EditorPane.kt")
        assertEquals("src/EditorPane.kt", rank("edpane", paths).first())
    }

    @Test
    fun `a name that starts with the query beats one that contains it`() {
        assertEquals(listOf("main.py", "domain.py"), rank("main", listOf("domain.py", "main.py")))
    }

    @Test
    fun `camel case humps and separators count as word starts`() {
        val hit = PathMatcher.score("ep", "src/EditorPane.kt")!!
        assertArrayEquals(intArrayOf(4, 10), hit.matched)
        val snake = PathMatcher.score("fh", "lib/file_helpers.py")!!
        assertArrayEquals(intArrayOf(4, 9), snake.matched)
    }

    @Test
    fun `the tightest window is chosen not the first greedy one`() {
        val hit = PathMatcher.score("ab", "xaxxab")!!
        assertArrayEquals(intArrayOf(4, 5), hit.matched)
    }

    @Test
    fun `a path that lacks the characters in order does not match`() {
        assertNull(PathMatcher.score("zq", "src/main.kt"))
        assertNull(PathMatcher.score("kt.", "main.kt"))
        assertNotNull(PathMatcher.score("MAIN", "src/main.kt"))
    }

    @Test
    fun `whitespace in the query is ignored`() {
        assertNotNull(PathMatcher.score("ed pane", "EditorPane.kt"))
    }

    @Test
    fun `an empty query lists recent files first then the rest`() {
        val paths = listOf("a.kt", "b.kt", "c.kt", "d.kt")
        assertEquals(listOf("c.kt", "a.kt", "b.kt", "d.kt"), rank("", paths, recent = listOf("c.kt", "a.kt", "gone.kt")))
        assertEquals(listOf("c.kt", "a.kt"), rank("  ", paths, recent = listOf("c.kt", "a.kt"), limit = 2))
    }

    @Test
    fun `on an otherwise equal match the recent file wins`() {
        val paths = listOf("a/util.kt", "b/util.kt")
        assertEquals(listOf("b/util.kt", "a/util.kt"), rank("util", paths, recent = listOf("b/util.kt")))
    }

    @Test
    fun `recency never outranks a clearly better match`() {
        val paths = listOf("lib/pxaxrxsxexr.kt", "src/parser.kt")
        assertEquals("src/parser.kt", rank("parser", paths, recent = listOf("lib/pxaxrxsxexr.kt")).first())
    }

    @Test
    fun `results are limited`() {
        val paths = (1..50).map { "dir/file$it.kt" }
        assertEquals(5, rank("file", paths, limit = 5).size)
    }

    @Test
    fun `a shorter path ranks first among equal matches`() {
        assertEquals(listOf("a.kt", "long/dir/a.kt"), rank("a", listOf("long/dir/a.kt", "a.kt")))
    }
}
