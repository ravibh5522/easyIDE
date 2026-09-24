package dev.easyide.app.ui.shell.ext

import androidx.compose.ui.unit.dp

/**
 * The fixed measurements and timings of extension views, in one place. Spacing, radii and type come from `Kit`;
 * what is here is geometry no appearance property changes, plus the limits that keep a view cheap to draw.
 */
object ExtViewTokens {
    /** Leading glyphs and status dots in a row. */
    val glyph = 20.dp
    val dot = 8.dp

    /** A sparkline and the tallest an inline log, code block or image may grow before it scrolls. */
    val sparkline = 40.dp
    val inlineLogMax = 240.dp
    val codeMax = 320.dp
    val imageDefault = 160.dp

    /** How much of the width a chat bubble may take, so the two speakers stay visibly apart. */
    const val BUBBLE_MAX_FRACTION = 0.86f

    /** A field bound to data commits after this quiet period, so a filter follows typing without redrawing on every key. */
    const val COMMIT_DEBOUNCE_MS = 250L

    /** A followed log or chat scrolls to the end while the reader is within this many items of it. */
    const val FOLLOW_SLACK = 2

    /** A weighted layout child with no explicit weight takes this share. */
    const val DEFAULT_WEIGHT = 1f
}
