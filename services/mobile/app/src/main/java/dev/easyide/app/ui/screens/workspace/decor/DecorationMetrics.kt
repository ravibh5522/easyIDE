package dev.easyide.app.ui.screens.workspace.decor

import androidx.compose.ui.unit.dp

/** Every size the decoration painters and the popup use, in one table. */
internal object DecorationMetrics {
    /** Half the peak-to-peak height of the squiggle wave. */
    val squiggleAmplitude = 1.5.dp

    /** One full up-and-down period of the squiggle wave. */
    val squiggleWavelength = 4.dp

    val squiggleStroke = 1.dp

    /**
     * Width a zero-length diagnostic (a missing token, an error at end of file) is drawn
     * with, so it is visible at all.
     */
    val squiggleMinWidth = 6.dp

    /** Hint severity is three dots under the start of the range, as in VS Code. */
    const val HINT_DOT_COUNT = 3
    val hintDotRadius = 1.dp
    val hintDotSpacing = 3.dp

    /** Lane at the gutter's left edge where the one glyph per line is drawn. */
    val gutterLaneWidth = 16.dp
    val gutterIconSize = 12.dp

    /** Space between a line's last character and its end-of-line inlay hints. */
    val ghostTextGap = 12.dp
    val ghostTextCorner = 3.dp
    val ghostTextPadding = 3.dp

    /** Separator between several inlay hints collected at one line's end. */
    const val GHOST_TEXT_SEPARATOR = "  "

    /**
     * Measured ghost-text labels kept by the painter's TextMeasurer. A viewport rarely
     * shows more distinct hint lines than this, so a redraw re-measures nothing.
     */
    const val GHOST_TEXT_MEASURE_CACHE = 64

    /** Minimum distance kept between a popup and the viewport edges. */
    val popupMargin = 4.dp

    /** Gap between a popup and the line it is anchored to. */
    val popupAnchorGap = 2.dp

    val popupElevation = 6.dp
    val popupBorder = 1.dp
}
