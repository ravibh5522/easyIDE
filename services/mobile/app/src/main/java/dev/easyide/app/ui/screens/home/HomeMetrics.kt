package dev.easyide.app.ui.screens.home

import androidx.compose.ui.unit.dp

/** Sizes, timings and addresses specific to Home; spacing, radii and colours come from `Kit`. */
internal object HomeMetrics {
    val monogramPage = 56.dp

    /** How strongly a project's tile colour tints its background; the letters stay in the text colour for contrast. */
    const val MONOGRAM_TINT_ALPHA = 0.22f

    val skeletonValueWidth = 96.dp

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
