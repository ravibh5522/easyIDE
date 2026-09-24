package dev.easyide.app.ui.shell.host

import androidx.compose.ui.unit.dp

/**
 * The fixed measurements of the shell chrome, in one place (layout-spec.md section 5). Spacing,
 * radii, row, tab, rail and status heights come from `Kit.space`, `Kit.radius` and `Kit.control`
 * (density.md 2); what is here is the geometry no appearance property changes.
 */
object ShellTokens {
    /** A rail that shows labels is this much wider than the icon-only rail token. */
    val railLabelExtra = 16.dp

    /** A document tab never grows past this; a longer name ends in an ellipsis (U-DEN-05). */
    val tabMaxWidth = 220.dp

    val badgeDot = 8.dp
    val badgeCount = 16.dp

    /** The drag target of a pane edge (LayoutTokens.splitterGrab) is wider than the hairline it draws. */
    val splitterLine = 1.dp

    /** The least a truncatable name in the status strip is squeezed to. */
    val statusNameMin = 64.dp

    /** The primary panel never takes more than this share of the window (layout-spec.md section 4.3: 200dp to 45%). */
    const val PANEL_MAX_FRACTION = 0.45f

    /** How long a toast stays (identity.md 10: 3 s), and how many wait behind the one shown. */
    const val TOAST_MS = 3000L
    const val TOAST_QUEUE_MAX = 4

    /** Writes of the shell snapshot collapse into one after this quiet period. */
    const val SAVE_DEBOUNCE_MS = 400L
}
