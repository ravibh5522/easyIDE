package dev.easyide.sandbox.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HunkPatchTest {

    private fun hunks(diff: String) = (UnifiedDiffParser.parse("f.txt", diff) as FileDiff.Text).hunks

    private val original = (1..12).joinToString("\n", postfix = "\n") { "l$it" }

    private val twoHunks = """
        diff --git a/f.txt b/f.txt
        --- a/f.txt
        +++ b/f.txt
        @@ -1,4 +1,4 @@
         l1
        -l2
        +L2
         l3
         l4
        @@ -9,4 +9,5 @@
         l9
         l10
        +new
         l11
         l12
    """.trimIndent() + "\n"

    @Test fun `parser reads both hunks with counts and lines`() {
        val h = hunks(twoHunks)
        assertEquals(2, h.size)
        assertEquals(1, h[0].oldStart); assertEquals(4, h[0].oldCount); assertEquals(4, h[0].newCount)
        assertEquals(9, h[1].oldStart); assertEquals(5, h[1].newCount)
        assertEquals(listOf(DiffLineKind.CONTEXT, DiffLineKind.REMOVED, DiffLineKind.ADDED, DiffLineKind.CONTEXT, DiffLineKind.CONTEXT), h[0].lines.map { it.kind })
    }

    @Test fun `applying one hunk leaves the other untouched`() {
        val h = hunks(twoHunks)
        val once = HunkPatch.apply(original, h[0])
        assertTrue(once.contains("L2") && !once.contains("new"))
        val secondNeedsShift = HunkPatch.apply(original, h[1])
        assertTrue(secondNeedsShift.contains("new") && !secondNeedsShift.contains("L2"))
    }

    @Test fun `revert undoes apply exactly`() {
        val h = hunks(twoHunks)[1]
        assertEquals(original, HunkPatch.revert(HunkPatch.apply(original, h), h))
    }

    @Test fun `a stale hunk is rejected instead of landing on the wrong lines`() {
        val h = hunks(twoHunks)[0]
        val changed = original.replace("l3", "different")
        try {
            HunkPatch.apply(changed, h)
            fail("expected HunkMismatchException")
        } catch (expected: HunkMismatchException) {
        }
    }

    @Test fun `pure insertion into an empty file`() {
        val h = hunks("@@ -0,0 +1,2 @@\n+a\n+b\n")[0]
        assertEquals("a\nb\n", HunkPatch.apply("", h))
        assertEquals("", HunkPatch.revert("a\nb\n", h))
    }

    @Test fun `pure deletion of everything`() {
        val h = hunks("@@ -1,2 +0,0 @@\n-a\n-b\n")[0]
        assertEquals("", HunkPatch.apply("a\nb\n", h))
    }

    @Test fun `insertion at count zero goes after the named line`() {
        val h = hunks("@@ -1,0 +2 @@\n+mid\n")[0]
        assertEquals("a\nmid\nb\n", HunkPatch.apply("a\nb\n", h))
    }

    @Test fun `missing final newline is tracked through the marker`() {
        val diff = "@@ -1,2 +1,2 @@\n a\n-b\n\\ No newline at end of file\n+c\n\\ No newline at end of file\n"
        val h = hunks(diff)[0]
        assertEquals("a\nc", HunkPatch.apply("a\nb", h))
        assertEquals("a\nb", HunkPatch.revert("a\nc", h))
    }

    @Test fun `adding a final newline`() {
        val diff = "@@ -1 +1 @@\n-a\n\\ No newline at end of file\n+a\n"
        val h = hunks(diff)[0]
        assertEquals("a\n", HunkPatch.apply("a", h))
        assertEquals("a", HunkPatch.revert("a\n", h))
    }

    @Test fun `carriage returns are preserved`() {
        val h = hunks("@@ -1,2 +1,2 @@\n a\r\n-b\r\n+c\r\n")[0]
        assertEquals("a\r\nc\r\n", HunkPatch.apply("a\r\nb\r\n", h))
    }

    @Test fun `content lines that look like headers stay content`() {
        val h = hunks("@@ -1,2 +1,2 @@\n--- not a header\n-+++ x\n+@@ -1 +1 @@\n+tail\n")
        assertEquals(1, h.size)
        assertEquals(4, h[0].lines.size)
    }

    @Test fun `binary and empty diffs`() {
        assertTrue(UnifiedDiffParser.parse("p", "diff --git a/p b/p\nBinary files a/p and b/p differ\n") is FileDiff.Binary)
        assertTrue((UnifiedDiffParser.parse("p", "diff --git a/p b/p\nnew file mode 100644\n") as FileDiff.Text).hunks.isEmpty())
    }

    @Test fun `header round trips`() {
        val h = hunks("@@ -3 +3,2 @@ fun main()\n-a\n+b\n+c\n")[0]
        assertEquals("@@ -3 +3,2 @@ fun main()", h.header)
    }
}
