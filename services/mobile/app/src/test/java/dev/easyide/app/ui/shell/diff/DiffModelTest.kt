package dev.easyide.app.ui.shell.diff

import androidx.compose.ui.text.AnnotatedString
import dev.easyide.app.ui.screens.workspace.git.DiffRow
import dev.easyide.sandbox.git.DiffHunk
import dev.easyide.sandbox.git.DiffLine
import dev.easyide.sandbox.git.DiffLineKind
import dev.easyide.sandbox.git.FileDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffModelTest {
    private fun ctx(text: String) = DiffLine(DiffLineKind.CONTEXT, text)
    private fun add(text: String) = DiffLine(DiffLineKind.ADDED, text)
    private fun del(text: String) = DiffLine(DiffLineKind.REMOVED, text)

    private val first = DiffHunk(1, 3, 1, 3, "", listOf(ctx("a"), del("b"), add("B"), ctx("c")))
    private val second = DiffHunk(20, 2, 20, 3, "fun f", listOf(ctx("x"), add("y"), ctx("z")))
    private val diff = FileDiff.Text("f.kt", listOf(first, second))

    @Test fun `unified is one header then one item per line`() {
        val m = DiffModel.of(diff, DiffMode.UNIFIED)
        assertEquals(listOf(0, 5), m.headers)
        assertEquals(1 + 4 + 1 + 3, m.items.size)
        assertTrue(m.items[0] is DiffItem.Header)
        assertTrue(m.items[1] is DiffItem.Unified)
    }

    @Test fun `side by side pairs a removed line with the added one that follows`() {
        val m = DiffModel.of(diff, DiffMode.SIDE_BY_SIDE)
        // header, ctx a, (b|B), ctx c, header, ctx x, (|y), ctx z
        assertEquals(listOf(0, 4), m.headers)
        val pair = m.items[2] as DiffItem.Split
        assertEquals("b", pair.row.left?.text)
        assertEquals("B", pair.row.right?.text)
        assertNull((m.items[6] as DiffItem.Split).row.left)
    }

    @Test fun `hunk navigation steps between headers from any scroll position`() {
        val m = DiffModel.of(diff, DiffMode.UNIFIED)
        assertEquals(0, m.nextHunk(-1))
        assertEquals(5, m.nextHunk(0))
        assertEquals(5, m.nextHunk(3))
        assertNull(m.nextHunk(5))
        assertNull(m.previousHunk(0))
        assertEquals(0, m.previousHunk(3))
        assertEquals(0, m.previousHunk(5))
        assertEquals(5, m.previousHunk(8))
    }

    @Test fun `the hunk under the first visible item is counted from zero`() {
        val m = DiffModel.of(diff, DiffMode.UNIFIED)
        assertEquals(0, m.hunkAt(0))
        assertEquals(0, m.hunkAt(4))
        assertEquals(1, m.hunkAt(5))
        assertEquals(1, m.hunkAt(99))
    }

    @Test fun `a diff without hunks has no items and nowhere to go`() {
        val m = DiffModel.of(FileDiff.Text("empty.txt", emptyList()), DiffMode.UNIFIED)
        assertTrue(m.items.isEmpty())
        assertNull(m.nextHunk(0))
        assertNull(m.previousHunk(0))
        assertEquals(-1, m.hunkAt(0))
    }

    @Test fun `rows are numbered per hunk and keep the changed words`() {
        val rows = DiffModel.of(diff, DiffMode.UNIFIED).rows
        assertEquals(listOf(1, 2, null, 3), rows[0].map { it.oldNo })
        assertEquals(listOf(1, null, 2, 3), rows[0].map { it.newNo })
        assertEquals(0..0, rows[0][1].emphasis)
    }

    @Test fun `the layout mode follows the documents own width`() {
        assertEquals(DiffMode.UNIFIED, DiffMode.of(360f))
        assertEquals(DiffMode.UNIFIED, DiffMode.of(839f))
        assertEquals(DiffMode.SIDE_BY_SIDE, DiffMode.of(840f))
        assertEquals(DiffMode.SIDE_BY_SIDE, DiffMode.of(1280f))
    }

    @Test fun `syntax sides hold each sides lines and keep hunks apart`() {
        val (old, new) = DiffSyntax.sides(DiffModel.of(diff, DiffMode.UNIFIED).rows)
        assertEquals("a\nb\nc\n\nx\nz", old.text)
        assertEquals("a\nB\nc\n\nx\ny\nz", new.text)
        assertEquals(listOf(true, true, true, false, true, true), old.rows.map { it != null })
    }

    @Test fun `styled slices map back to their rows`() {
        val rows = DiffModel.of(diff, DiffMode.UNIFIED).rows
        val (old, new) = DiffSyntax.sides(rows)
        val slices = DiffSyntax.slices(new, AnnotatedString(new.text))
        val added = rows[0].first { it.kind == DiffLineKind.ADDED }
        assertEquals("B", slices[added]?.text)
        assertEquals(new.rows.filterNotNull().size, slices.size)
        assertEquals("b", DiffSyntax.slices(old, AnnotatedString(old.text))[rows[0].first { it.kind == DiffLineKind.REMOVED }]?.text)
    }

    @Test fun `an added file has only a new side`() {
        val added = FileDiff.Text("n.txt", listOf(DiffHunk(0, 0, 1, 2, "", listOf(add("one"), add("two")))))
        val (old, new) = DiffSyntax.sides(DiffModel.of(added, DiffMode.UNIFIED).rows)
        assertEquals("", old.text)
        assertEquals("one\ntwo", new.text)
    }

    @Test fun `the paint picks the side a line belongs to`() {
        val row = DiffRow(DiffLineKind.REMOVED, "b", 2, null)
        val paint = DiffPaint(mapOf(row to AnnotatedString("b-old")), mapOf(row to AnnotatedString("b-new")))
        assertEquals("b-old", paint.styled(row, oldSide = true)?.text)
        assertEquals("b-new", paint.styled(row, oldSide = false)?.text)
        assertNull(DiffPaint.NONE.styled(row, true))
    }
}
