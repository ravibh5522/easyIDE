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

    /** Glyph margin at the gutter's left edge where the one glyph per line is drawn (VS Code: 18). */
    val gutterLaneWidth = 18.dp

    /** The glyph margin on a window narrower than [compactBelow], where every dp of text width counts. */
    val gutterLaneWidthCompact = 14.dp
    val gutterIconSize = 14.dp

    /** Editors narrower than this use the compact gutter. */
    val compactBelow = 360.dp

    /** Line numbers reserve room for this many digits; VS Code reserves 5, this is tighter. */
    const val MIN_LINE_NUMBER_DIGITS = 3
    const val MIN_LINE_NUMBER_DIGITS_COMPACT = 2

    /** Gap above the first and below the last line. */
    val textPaddingVertical = 2.dp

    /** Outline of the bracket pair and of the caret line's border. */
    val outlineStroke = 1.dp

    /** Vertical indent guides. */
    val indentGuideStroke = 1.dp

    /** Overlay scrollbar: the thumb, its shortest length, and how it fades after a scroll. */
    val scrollbarWidth = 10.dp
    val scrollbarMinThumb = 24.dp
    const val SCROLLBAR_FADE_DELAY_MS = 900L
    const val SCROLLBAR_FADE_MS = 250
    const val SCROLLBAR_THUMB_ALPHA = 0.5f

    /** Half-period of the caret blink (VS Code's `blink`). */
    const val CARET_BLINK_MS = 500L

    /** A block caret is translucent so the character under it stays readable. */
    const val BLOCK_CARET_ALPHA = 0.5f
    val underlineCaretHeight = 2.dp

    /** Space between a line's last character and its end-of-line inlay hints. */
    val ghostTextGap = 12.dp
    val ghostTextCorner = 3.dp
    val ghostTextPadding = 3.dp

    /** Separator between several inlay hints collected at one line's end. */
    const val GHOST_TEXT_SEPARATOR = "  "

    /**
     * Measured labels kept by the editor's TextMeasurer, shared by end-of-line hints and the line
     * numbers. A viewport shows a few dozen of each, so a redraw re-measures nothing.
     */
    const val MEASURE_CACHE = 128

    /** Minimum distance kept between a popup and the viewport edges. */
    val popupMargin = 4.dp

    /** Gap between a popup and the line it is anchored to. */
    val popupAnchorGap = 2.dp

    val popupBorder = 1.dp

    /** Widgets in the editor are nearly square (VS Code: 3px); never a soft card. */
    val popupRadius = 2.dp
}
