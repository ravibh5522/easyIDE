package dev.easyide.app.ui.screens.home

import androidx.compose.ui.unit.dp

/** Sizes, timings and addresses specific to Home; spacing, radii and colours come from `Kit`. */
internal object HomeMetrics {
    val monogramPage = 56.dp

    /** How strongly a project's tile colour tints its background; the letters stay in the text colour for contrast. */
    const val MONOGRAM_TINT_ALPHA = 0.22f

    val listPaneMinWidth = 320.dp
    val listPaneMaxWidth = 440.dp
    val skeletonValueWidth = 96.dp

    /** Weight of the list pane against the stage at expanded width. */
    const val LIST_PANE_WEIGHT = 0.4f
    const val STAGE_WEIGHT = 0.6f

    const val RECENT_PLACEHOLDER_ROWS = 3
    const val SKELETON_TITLE_FRACTION = 0.5f
    const val SKELETON_SUBTITLE_FRACTION = 0.7f

    /** How often "2m" labels are recomputed. */
    const val CLOCK_TICK_MS = 30_000L

    /** How long a confirmation stays before it clears itself (identity.md 10: toast 3s). */
    const val MESSAGE_MS = 3_000L

    /** The Sandbox settings document, where environments are managed. */
    const val SANDBOX_SETTINGS_URI = "easyide://settings/sandbox"
}
