package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.theme.GitColors
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.sandbox.git.DiffLineKind

/**
 * Line rendering for the diff screen. Line numbers stay put while the text
 * scrolls sideways, and every row of one column shares a single scroll state,
 * so a long line moves the whole diff rather than one row of it.
 */

@Composable
internal fun UnifiedLine(row: DiffRow, scroll: ScrollState) {
    val git = editorColors.git
    Row(modifier = Modifier.fillMaxWidth().background(lineTint(row.kind, git))) {
        LineNumber(row.oldNo)
        LineNumber(row.newNo)
        Text(
            text = sign(row.kind),
            style = codeStyle(),
            color = editorColors.textMuted,
            modifier = Modifier.width(GitUi.signWidth),
            textAlign = TextAlign.Center,
        )
        CodeText(row, scroll, Modifier.weight(1f))
    }
}

/** One half of a side-by-side row; an unpaired line leaves the opposite half as a blank raised block. */
@Composable
internal fun SplitHalf(row: DiffRow?, oldSide: Boolean, scroll: ScrollState, modifier: Modifier) {
    val colors = editorColors
    if (row == null) {
        Box(modifier = modifier.background(colors.raised))
        return
    }
    Row(modifier = modifier.background(lineTint(row.kind, colors.git))) {
        LineNumber(if (oldSide) row.oldNo else row.newNo)
        CodeText(row, scroll, Modifier.weight(1f))
    }
}

@Composable
private fun LineNumber(number: Int?) {
    Text(
        text = number?.toString().orEmpty(),
        style = codeStyle(),
        color = editorColors.gutterText,
        textAlign = TextAlign.End,
        modifier = Modifier.width(GitUi.lineNumberWidth),
    )
}

@Composable
private fun CodeText(row: DiffRow, scroll: ScrollState, modifier: Modifier) {
    val git = editorColors.git
    val text = remember(row) { emphasised(row, emphasisTint(row.kind, git)) }
    Box(modifier = modifier.horizontalScroll(scroll)) {
        Text(
            text = text,
            style = codeStyle(),
            color = editorColors.plainText,
            softWrap = false,
            modifier = Modifier.padding(start = Spacing.xs),
        )
    }
}

@Composable
private fun codeStyle() = Kit.text.monoSmall

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

private fun emphasised(row: DiffRow, tint: Color): AnnotatedString = buildAnnotatedString {
    append(row.text)
    row.emphasis?.let { range ->
        val end = (range.last + 1).coerceAtMost(row.text.length)
        if (range.first < end) addStyle(SpanStyle(background = tint), range.first, end)
    }
}
