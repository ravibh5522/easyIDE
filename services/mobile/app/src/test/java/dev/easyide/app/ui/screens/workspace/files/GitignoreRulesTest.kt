package dev.easyide.app.ui.screens.workspace.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitignoreRulesTest {

    private fun rules(vararg lines: String) = GitignoreRules.parse(lines.joinToString("\n"))

    private fun ignored(r: GitignoreRules, path: String, dir: Boolean = false) = r.verdict(path, dir)

    @Test
    fun `comments blank lines and an empty file match nothing`() {
        val r = rules("# comment", "", "   ")
        assertTrue(r.isEmpty)
        assertNull(ignored(r, "anything"))
    }

    @Test
    fun `a pattern without a slash matches at any depth`() {
        val r = rules("*.log")
        assertEquals(true, ignored(r, "a.log"))
        assertEquals(true, ignored(r, "x/y/a.log"))
        assertNull(ignored(r, "a.txt"))
    }

    @Test
    fun `a leading or inner slash anchors to the gitignore's directory`() {
        val r = rules("/dist", "docs/*.md")
        assertEquals(true, ignored(r, "dist", dir = true))
        assertNull(ignored(r, "src/dist", dir = true))
        assertEquals(true, ignored(r, "docs/a.md"))
        assertNull(ignored(r, "x/docs/a.md"))
        assertNull(ignored(r, "docs/sub/a.md"))
    }

    @Test
    fun `a trailing slash matches directories only`() {
        val r = rules("build/")
        assertEquals(true, ignored(r, "build", dir = true))
        assertEquals(true, ignored(r, "a/build", dir = true))
        assertNull(ignored(r, "build", dir = false))
    }

    @Test
    fun `negation re-includes and the last match wins`() {
        val r = rules("*.log", "!keep.log")
        assertEquals(true, ignored(r, "a.log"))
        assertEquals(false, ignored(r, "keep.log"))
        val reversed = rules("!keep.log", "*.log")
        assertEquals(true, ignored(reversed, "keep.log"))
    }

    @Test
    fun `double star matches across directories`() {
        val r = rules("**/tmp", "a/**/b", "out/**")
        assertEquals(true, ignored(r, "tmp", dir = true))
        assertEquals(true, ignored(r, "x/y/tmp", dir = true))
        assertEquals(true, ignored(r, "a/b"))
        assertEquals(true, ignored(r, "a/x/y/b"))
        assertEquals(true, ignored(r, "out/x/y"))
        assertNull(ignored(r, "b"))
    }

    @Test
    fun `question mark and character classes`() {
        val r = rules("f?.txt", "[ab]*.c", "[!x]y")
        assertEquals(true, ignored(r, "f1.txt"))
        assertNull(ignored(r, "f12.txt"))
        assertEquals(true, ignored(r, "a1.c"))
        assertNull(ignored(r, "c1.c"))
        assertEquals(true, ignored(r, "zy"))
        assertNull(ignored(r, "xy"))
    }

    @Test
    fun `escapes and trailing spaces`() {
        val r = rules("\\#file", "\\!bang", "space\\ ", "trim   ")
        assertEquals(true, ignored(r, "#file"))
        assertEquals(true, ignored(r, "!bang"))
        assertEquals(true, ignored(r, "space "))
        assertEquals(true, ignored(r, "trim"))
        assertNull(ignored(r, "trim   "))
    }

    @Test
    fun `regex metacharacters in a pattern are literal`() {
        val r = rules("a+b(1).txt")
        assertEquals(true, ignored(r, "a+b(1).txt"))
        assertNull(ignored(r, "aab1.txt"))
    }

    @Test
    fun `crlf line endings are tolerated`() {
        val r = GitignoreRules.parse("*.log\r\nbuild/\r\n")
        assertEquals(true, ignored(r, "a.log"))
        assertEquals(true, ignored(r, "build", dir = true))
    }

    @Test
    fun `nothing inside an ignored directory is shown whatever its own rules say`() {
        val index = IgnoreIndex.EMPTY
            .with("", GitignoreRules.parse("build/\n*.log"))
            .with("build", GitignoreRules.parse("!keep.txt"))
        assertTrue(index.isIgnored("build", true))
        assertTrue(index.isIgnored("build/x/y.txt", false))
        assertTrue(index.isIgnored("build/keep.txt", false))
        assertTrue(index.isIgnored("a.log", false))
        assertFalse(index.isIgnored("src/a.kt", false))
    }

    @Test
    fun `a deeper gitignore overrides a shallower one`() {
        val index = IgnoreIndex.EMPTY
            .with("", GitignoreRules.parse("*.log"))
            .with("sub", GitignoreRules.parse("!important.log\n/local"))
        assertTrue(index.isIgnored("a.log", false))
        assertTrue(index.isIgnored("sub/other.log", false))
        assertFalse(index.isIgnored("sub/important.log", false))
        assertTrue(index.isIgnored("sub/local", true))
        assertFalse(index.isIgnored("local", true))
    }

    @Test
    fun `without drops a directory's rules`() {
        val index = IgnoreIndex.EMPTY.with("", GitignoreRules.parse("*.log"))
        assertFalse(index.without("").isIgnored("a.log", false))
    }
}
