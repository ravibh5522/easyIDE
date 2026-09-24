package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.sandbox.git.DiffHunk
import dev.easyide.sandbox.git.DiffLine
import dev.easyide.sandbox.git.DiffLineKind.ADDED
import dev.easyide.sandbox.git.DiffLineKind.CONTEXT
import dev.easyide.sandbox.git.DiffLineKind.REMOVED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiffLayoutTest {

    private fun hunk(oldStart: Int, newStart: Int, vararg lines: DiffLine) = DiffHunk(
        oldStart, lines.count { it.kind != ADDED }, newStart, lines.count { it.kind != REMOVED }, "", lines.toList(),
    )

    private fun l(kind: dev.easyide.sandbox.git.DiffLineKind, text: String) = DiffLine(kind, text)

    @Test fun `numbers follow each side independently`() {
        val rows = DiffLayout.rows(hunk(10, 10, l(CONTEXT, "a"), l(REMOVED, "b"), l(ADDED, "B"), l(ADDED, "extra"), l(CONTEXT, "c")))
        assertEquals(listOf(10 to 10, 11 to null, null to 11, null to 12, 12 to 13), rows.map { it.oldNo to it.newNo })
    }

    @Test fun `a zero count hunk starts after the named line`() {
        val rows = DiffLayout.rows(DiffHunk(0, 0, 1, 2, "", listOf(l(ADDED, "x"), l(ADDED, "y"))))
        assertEquals(listOf(1, 2), rows.map { it.newNo })
    }

    @Test fun `a renamed identifier is emphasised whole`() {
        val (old, new) = DiffLayout.rows(hunk(1, 1, l(REMOVED, "val fooBar = 1"), l(ADDED, "val fooBaz = 1")))
        assertEquals("fooBar", old.text.substring(old.emphasis!!))
        assertEquals("fooBaz", new.text.substring(new.emphasis!!))
    }

    @Test fun `an insertion emphasises only the inserted text`() {
        val (old, new) = IntraLineDiff.changedSpans("call(x)", "call(x, y)")
        assertNull(old)
        assertEquals(", y", "call(x, y)".substring(new!!))
    }

    @Test fun `identical lines have no emphasis`() {
        assertEquals(null to null, IntraLineDiff.changedSpans("same", "same"))
    }

    @Test fun `unpaired lines carry no emphasis`() {
        val rows = DiffLayout.rows(hunk(1, 1, l(REMOVED, "only removed")))
        assertNull(rows.single().emphasis)
    }

    @Test fun `split view pairs a removed run with the added run that follows`() {
        val rows = DiffLayout.rows(
            hunk(1, 1, l(CONTEXT, "a"), l(REMOVED, "b"), l(REMOVED, "c"), l(ADDED, "B"), l(CONTEXT, "d")),
        )
        val split = DiffLayout.split(rows)
        assertEquals(4, split.size)
        assertEquals("a", split[0].left?.text); assertEquals("a", split[0].right?.text)
        assertEquals("b", split[1].left?.text); assertEquals("B", split[1].right?.text)
        assertEquals("c", split[2].left?.text); assertNull(split[2].right)
        assertEquals("d", split[3].right?.text)
    }

    @Test fun `split view leaves the old side empty for a pure addition`() {
        val split = DiffLayout.split(DiffLayout.rows(hunk(1, 1, l(ADDED, "x"), l(ADDED, "y"))))
        assertEquals(listOf(null, null), split.map { it.left })
        assertEquals(listOf("x", "y"), split.map { it.right?.text })
    }
}
