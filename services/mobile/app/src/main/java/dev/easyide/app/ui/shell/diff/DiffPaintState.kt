package dev.easyide.app.ui.shell.diff

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.AnnotatedString
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.screens.workspace.git.DiffRow
import dev.easyide.app.ui.screens.workspace.syntax.TextMateHighlighter
import dev.easyide.app.ui.theme.SyntaxColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The diff's syntax colours, computed off the composition thread with the same highlighter the editor
 * uses, so a file reads the same in both. The rows show plain until the first pass lands, so opening a
 * diff never waits on a grammar. A file with no grammar simply keeps the plain text.
 */
@Composable
internal fun rememberDiffPaint(path: String, rows: List<List<DiffRow>>): DiffPaint {
    val colors = Kit.colors.syntax
    val paint by produceState(DiffPaint.NONE, path, rows, colors) {
        val (old, new) = DiffSyntax.sides(rows)
        value = withContext(Dispatchers.Default) {
            DiffPaint(coloured(path, OLD_SIDE, old, colors), coloured(path, NEW_SIDE, new, colors))
        }
    }
    return paint
}

private const val OLD_SIDE = "old"
private const val NEW_SIDE = "new"

/** The tokenizer keeps its state per key, so each side of each file has its own. */
private fun coloured(path: String, side: String, text: SideText, colors: SyntaxColors): Map<DiffRow, AnnotatedString> {
    val styled = TextMateHighlighter.highlight(
        key = "diff:$side:$path",
        source = text.text,
        fileName = path.substringAfterLast('/'),
        colors = colors,
        firstLine = 0,
        lastLine = text.rows.size,
    )
    return DiffSyntax.slices(text, styled)
}
