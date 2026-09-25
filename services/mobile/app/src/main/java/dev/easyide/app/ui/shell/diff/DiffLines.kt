package dev.easyide.app.ui.shell.diff

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.screens.workspace.git.DiffRow
import dev.easyide.app.ui.screens.workspace.git.GitUi
import dev.easyide.app.ui.theme.GitColors
import dev.easyide.sandbox.git.DiffLineKind

/**
 * The colouring of a diff's lines: the file's syntax colours per side, the added or removed wash behind
 * a line, and a stronger wash on the words that differ from the paired line. Line numbers stay put while
 * the text scrolls sideways, and every row of one column shares a single scroll state, so a long line
 * moves the whole diff rather than one row of it.
 */
class DiffPaint(val old: Map<DiffRow, AnnotatedString>, val new: Map<DiffRow, AnnotatedString>) {
    /** A removed line is coloured from the old side; added and unchanged lines from the new one. */
    fun styled(row: DiffRow, oldSide: Boolean): AnnotatedString? = (if (oldSide) old else new)[row]

    companion object {
        val NONE = DiffPaint(emptyMap(), emptyMap())
    }
}

@Composable
internal fun UnifiedLine(row: DiffRow, paint: DiffPaint, scroll: ScrollState) {
    val git = Kit.colors.git
    Row(Modifier.fillMaxWidth().background(lineTint(row.kind, git))) {
        LineNumber(row.oldNo)
        LineNumber(row.newNo)
        BasicText(sign(row.kind), Modifier.width(GitUi.signWidth), style = codeStyle().copy(color = Kit.colors.textMuted, textAlign = TextAlign.Center))
        CodeText(row, paint.styled(row, oldSide = row.kind == DiffLineKind.REMOVED), scroll, Modifier.weight(1f))
    }
}

/** One half of a side-by-side row; an unpaired line leaves the opposite half as a blank raised block. */
@Composable
internal fun SplitHalf(row: DiffRow?, paint: DiffPaint, oldSide: Boolean, scroll: ScrollState, modifier: Modifier) {
    if (row == null) {
        Box(modifier.background(Kit.colors.raised))
        return
    }
    Row(modifier.background(lineTint(row.kind, Kit.colors.git))) {
        LineNumber(if (oldSide) row.oldNo else row.newNo)
        CodeText(row, paint.styled(row, oldSide), scroll, Modifier.weight(1f))
    }
}

@Composable
private fun LineNumber(number: Int?) {
    BasicText(
        number?.toString().orEmpty(),
        Modifier.width(GitUi.lineNumberWidth),
        style = codeStyle().copy(color = Kit.colors.gutterText, textAlign = TextAlign.End),
    )
}

@Composable
private fun CodeText(row: DiffRow, syntax: AnnotatedString?, scroll: ScrollState, modifier: Modifier) {
    val git = Kit.colors.git
    val wash = emphasisTint(row.kind, git)
    val text = remember(row, syntax, wash) { emphasised(row, syntax, wash) }
    Box(modifier.horizontalScroll(scroll)) {
        BasicText(text, Modifier.padding(start = Kit.space.xs), style = codeStyle().copy(color = Kit.colors.plainText), softWrap = false)
    }
}

@Composable
private fun codeStyle(): TextStyle = Kit.text.monoSmall

private fun sign(kind: DiffLineKind) = when (kind) {
    DiffLineKind.ADDED -> "+"
    DiffLineKind.REMOVED -> "-"
    DiffLineKind.CONTEXT -> " "
}

private fun lineTint(kind: DiffLineKind, git: GitColors): Color = when (kind) {
    DiffLineKind.ADDED -> git.added.copy(alpha = GitUi.LINE_TINT_ALPHA)
    DiffLineKind.REMOVED -> git.deleted.copy(alpha = GitUi.LINE_TINT_ALPHA)
    DiffLineKind.CONTEXT -> Color.Transparent
}

private fun emphasisTint(kind: DiffLineKind, git: GitColors): Color = when (kind) {
    DiffLineKind.ADDED -> git.added.copy(alpha = GitUi.EMPHASIS_TINT_ALPHA)
    DiffLineKind.REMOVED -> git.deleted.copy(alpha = GitUi.EMPHASIS_TINT_ALPHA)
    DiffLineKind.CONTEXT -> Color.Transparent
}

/** The line's syntax-coloured text (plain when the grammar had none) with the changed words washed over it. */
internal fun emphasised(row: DiffRow, syntax: AnnotatedString?, tint: Color): AnnotatedString = buildAnnotatedString {
    append(syntax?.takeIf { it.text == row.text } ?: AnnotatedString(row.text))
    row.emphasis?.let { range ->
        val end = (range.last + 1).coerceAtMost(row.text.length)
        if (range.first < end) addStyle(SpanStyle(background = tint), range.first, end)
    }
}
