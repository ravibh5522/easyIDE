package dev.easyide.app.ui.screens.home

import androidx.compose.ui.unit.dp

/** Sizes, timings and addresses specific to Home; spacing, radii and colours come from `Kit`. */
internal object HomeMetrics {
    val monogramPage = 40.dp

    /** How strongly a project's tile colour tints its background; the letters stay in the text colour for contrast. */
    const val MONOGRAM_TINT_ALPHA = 0.22f

    val skeletonValueWidth = 96.dp

    const val RECENT_PLACEHOLDER_ROWS = 3

    /** Projects the stage's summary lists; the panel beside it (or the Projects section) has the rest. */
    const val SUMMARY_PROJECTS = 5
    const val SKELETON_TITLE_FRACTION = 0.5f
    const val SKELETON_SUBTITLE_FRACTION = 0.7f

    /** How often "2m" labels are recomputed. */
    const val CLOCK_TICK_MS = 30_000L

    /** How long a confirmation stays before it clears itself (identity.md 10: toast 3s). */
    const val MESSAGE_MS = 3_000L

    /**
     * Stable ids (`kit:<id>` test tags) of the project row and the page's Open button. The baseline
     * profile generator finds them by resource id (`baselineprofile` module, which cannot import this).
     */
    const val PROJECT_ROW_ID = "project-row"
    const val PROJECT_OPEN_ID = "project-open"

    /** The Sandbox settings document, where environments are managed. */
    const val SANDBOX_SETTINGS_URI = "easyide://settings/sandbox"
}
