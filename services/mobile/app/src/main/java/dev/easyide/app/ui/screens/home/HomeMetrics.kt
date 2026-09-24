package dev.easyide.app.ui.screens.home

import androidx.compose.ui.unit.dp

/** Sizes specific to the Home screens; spacing and radii come from the theme tokens. */
internal object HomeMetrics {
    val monogramList = 44.dp
    val monogramDetail = 56.dp
    val languageDot = 8.dp
    val listPaneMinWidth = 320.dp
    val listPaneMaxWidth = 440.dp
    val singleColumnMaxWidth = 640.dp
    val detailMaxWidth = 560.dp
    val actionIcon = 18.dp

    /** How strongly a project's tile colour tints its background; the letters stay in the text colour for contrast. */
    const val MONOGRAM_TINT_ALPHA = 0.22f
    const val SKELETON_CARD_COUNT = 6
    const val SKELETON_TITLE_FRACTION = 0.5f
    const val SKELETON_SUBTITLE_FRACTION = 0.7f
    const val SKELETON_CHIP_FRACTION = 0.35f
    const val RECENT_PLACEHOLDER_ROWS = 3

    /** Weight of the list pane against the detail pane at expanded width. */
    const val LIST_PANE_WEIGHT = 0.4f
    const val DETAIL_PANE_WEIGHT = 0.6f

    /** How often "2m ago" labels are recomputed. */
    const val CLOCK_TICK_MS = 30_000L
}
