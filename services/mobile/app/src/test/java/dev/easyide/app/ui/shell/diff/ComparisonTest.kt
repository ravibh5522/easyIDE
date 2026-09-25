package dev.easyide.app.ui.shell.diff

import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.uri
import dev.easyide.sandbox.git.DiffEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonTest {
    private fun roundTrip(c: Comparison): Comparison? = Comparison.of(uri(c.uri.toString()))

    @Test fun `the unstaged and staged comparisons have their canonical uris`() {
        assertEquals("git-diff:///src/a.kt?base=index&head=worktree", Comparison.unstaged("src/a.kt").uri.toString())
        assertEquals("git-diff:///src/a.kt?base=HEAD&head=index", Comparison.staged("src/a.kt").uri.toString())
    }

    @Test fun `every kind of end round trips through the uri`() {
        val ends = listOf(DiffEnd.Rev("HEAD"), DiffEnd.Rev("HEAD~2"), DiffEnd.Rev("feature/x"), DiffEnd.Rev("a".repeat(40)), DiffEnd.Index, DiffEnd.Empty)
        for (base in ends) for (head in ends + DiffEnd.Worktree) {
            if (base == head || base == DiffEnd.Worktree || head == DiffEnd.Empty) continue
            val c = Comparison("d/f.txt", base, head)
            assertEquals("$base to $head", c, roundTrip(c))
        }
    }

    @Test fun `awkward paths are encoded and come back unchanged`() {
        for (path in listOf("my dir/a b.kt", "café/naïve.txt", "a#b/c?d.kt", "100%/x&y=z.kt", "dir/[x]+(y).kt")) {
            val c = Comparison.unstaged(path)
            val text = c.uri.toString()
            assertFalse("$text still has a raw space", ' ' in text)
            assertEquals(path, roundTrip(c)?.path)
        }
    }

    @Test fun `two spellings of one comparison are one uri`() {
        val a = uri("git-diff:///a.kt?head=index&base=HEAD")
        val b = uri("git-diff:///dir/../a.kt?base=HEAD&head=index")
        assertEquals(a, b)
        assertEquals(Comparison.staged("a.kt"), Comparison.of(a))
    }

    @Test fun `malformed comparisons are refused`() {
        val bad = listOf(
            "git-diff:///a.kt",
            "git-diff:///a.kt?base=HEAD",
            "git-diff:///a.kt?head=index",
            "git-diff:///a.kt?base=&head=index",
            "git-diff:///a.kt?base=index&head=index",
            "git-diff:///a.kt?base=worktree&head=index",
            "git-diff:///a.kt?base=HEAD&head=empty",
            "git-diff:///a.kt?base=HEAD&head=HEAD",
        )
        for (text in bad) assertNull(text, DocumentUri.parse(text)?.let { Comparison.of(it) })
        assertNull(Comparison.of(uri("file:///workspace/a.kt")))
        assertNull(DocumentUri.parse("git-diff://host/a.kt?base=HEAD&head=index"))
        assertNull(DocumentUri.parse("git-diff://?base=HEAD&head=index"))
    }

    @Test fun `a path a uri cannot carry has no uri instead of failing`() {
        assertNull(Comparison.unstaged("bad\nname.txt").uri)
        assertNull(Comparison.unstaged("../escape.txt").uri)
        assertNotNull(Comparison.unstaged("ok.txt").uri)
    }

    @Test fun `staged means HEAD against the index and nothing else`() {
        assertTrue(Comparison.staged("a").isStaged)
        assertFalse(Comparison.staged("a").isUnstaged)
        assertTrue(Comparison.unstaged("a").isUnstaged)
        assertFalse(Comparison("a", DiffEnd.Rev("abc123"), DiffEnd.Index).isStaged)
        assertFalse(Comparison("a", DiffEnd.Rev("HEAD"), DiffEnd.Worktree).let { it.isStaged || it.isUnstaged })
    }

    @Test fun `the reserved words are ends and every other word is a revision`() {
        assertEquals(DiffEnd.Index, DiffEnds.parse("index"))
        assertEquals(DiffEnd.Worktree, DiffEnds.parse("worktree"))
        assertEquals(DiffEnd.Empty, DiffEnds.parse("empty"))
        assertEquals(DiffEnd.Rev("main"), DiffEnds.parse("main"))
        assertNull(DiffEnds.parse(" "))
        assertNull(DiffEnds.parse(""))
    }

    @Test fun `a subject names the file and its sides`() {
        val s = Comparison("d/a.kt", DiffEnd.Rev("f".repeat(40)), DiffEnd.Worktree).subject
        assertEquals("d/a.kt", s.path)
        assertEquals(SideLabel.Named("fffffff"), s.left)
        assertEquals(SideLabel.Worktree, s.right)
        assertEquals(SideLabel.Nothing, Comparison("a", DiffEnd.Empty, DiffEnd.Rev("x")).subject.left)
    }
}
