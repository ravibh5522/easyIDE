package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.ui.unit.dp

/**
 * Layout and paint constants for the source-control UI that no shared theme
 * token covers. One place, so the diff screen and the panes agree.
 */
object GitUi {
    /** The streamed-output panel: enough for a screenful of progress, never the whole pane. */
    val outputMaxHeight = 160.dp

    /** Line-number columns of the diff gutter (four digits at the diff's font size). */
    val lineNumberWidth = 40.dp

    /** The +/- sign column of a unified diff line. */
    val signWidth = 16.dp

    /** Whole-line background of an added/removed diff line: a wash, so text stays readable in every theme. */
    const val LINE_TINT_ALPHA = 0.16f

    /** The changed span inside a paired line: stronger than the wash so it reads as the edit. */
    const val EMPHASIS_TINT_ALPHA = 0.40f

    /** The rule between the two halves of a side-by-side diff. */
    val dividerWidth = 1.dp

    /** Tallest a list inside a git sheet grows before it scrolls on its own, so the sheet's actions stay on screen. */
    val sheetListMaxHeight = 280.dp
}
