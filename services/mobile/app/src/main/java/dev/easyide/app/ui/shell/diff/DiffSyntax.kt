package dev.easyide.app.ui.shell.diff

import androidx.compose.ui.text.AnnotatedString
import dev.easyide.app.ui.screens.workspace.git.DiffRow
import dev.easyide.sandbox.git.DiffLineKind

/**
 * One side of a diff as source text, so the file's grammar can colour it. A diff only holds the lines
 * around each change, so each hunk becomes its own stretch of text, kept apart by a blank line that
 * belongs to no row; [rows] has one entry per line of [text], null for those separators.
 */
class SideText(val text: String, val rows: List<DiffRow?>)

/** Turns a diff's rows into two colourable texts and, once coloured, back into styled lines. */
object DiffSyntax {

    /** The old side (context and removed lines) and the new side (context and added lines) of every hunk. */
    fun sides(hunks: List<List<DiffRow>>): Pair<SideText, SideText> =
        side(hunks, DiffLineKind.ADDED) to side(hunks, DiffLineKind.REMOVED)

    private fun side(hunks: List<List<DiffRow>>, skip: DiffLineKind): SideText {
        val rows = ArrayList<DiffRow?>()
        hunks.forEachIndexed { i, hunk ->
            if (i > 0) rows += null
            hunk.filterTo(rows) { it.kind != skip }
        }
        return SideText(rows.joinToString("\n") { it?.text.orEmpty() }, rows)
    }

    /** Each row's slice of [styled], which is [side]'s text with colours added and so has the same length. */
    fun slices(side: SideText, styled: AnnotatedString): Map<DiffRow, AnnotatedString> {
        val out = HashMap<DiffRow, AnnotatedString>(side.rows.size)
        var start = 0
        for (row in side.rows) {
            val length = row?.text?.length ?: 0
            if (row != null) out[row] = styled.subSequence(start, start + length)
            start += length + 1
        }
        return out
    }
}
