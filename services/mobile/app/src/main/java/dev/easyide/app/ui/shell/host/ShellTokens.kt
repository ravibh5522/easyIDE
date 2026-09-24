package dev.easyide.app.ui.shell.host

import androidx.compose.ui.unit.dp

/**
 * The fixed measurements of the shell chrome, in one place (layout-spec.md section 5). Spacing,
 * radii and row heights come from `Kit.space`, `Kit.radius` and `Kit.control`; what is here is the
 * geometry no appearance property changes.
 */
object ShellTokens {
    /** One navigation cell: the bottom bar's height and a rail cell's height. */
    val navCell = 56.dp
    val railWidth = 56.dp
    val railWidthLabelled = 72.dp
    val navIcon = 24.dp

    val badgeDot = 8.dp
    val badgeCount = 16.dp

    /** The drag target of a pane edge (LayoutTokens.splitterGrab) is wider than the hairline it draws. */
    val splitterLine = 1.dp

    /** The primary panel never takes more than this share of the window (layout-spec.md section 4.3: 200dp to 45%). */
    const val PANEL_MAX_FRACTION = 0.45f

    /** How long a toast stays (identity.md 10: 3 s), and how many wait behind the one shown. */
    const val TOAST_MS = 3000L
    const val TOAST_QUEUE_MAX = 4

    /** Writes of the shell snapshot collapse into one after this quiet period. */
    const val SAVE_DEBOUNCE_MS = 400L
}
