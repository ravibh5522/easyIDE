package dev.easyide.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Layout tokens (decision 0019). One source per concern, so density, spacing
 * and corner decisions are made here rather than at call sites.
 */

/** The 4dp grid. [xxs] is the one half-step, for insets inside dense rows. */
object Spacing {
    val none = 0.dp
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

/** Corner radii: keys and badges, buttons and pills, cards, sheets. */
object Radius {
    val xs = 4.dp
    val s = 6.dp
    val m = 10.dp
    val l = 16.dp
}

/**
 * Shadow elevation for things that float. Resting surfaces separate by tone
 * (editor < panel < raised < overlay colour tokens), not shadow, so only
 * popups and dialogs use these.
 */
object Elevation {
    val flat = 0.dp
    val overlay = 6.dp
    val modal = 12.dp
}

/** Line widths: region hairlines and the accent bars that mark focus. */
object Stroke {
    val hairline = 1.dp
    val accentBar = 2.dp
}

/** Icon glyph sizes, smallest (inline with 11sp text) to largest (rail). */
object IconSize {
    val xs = 14.dp
    val s = 16.dp
    val m = 18.dp
    val l = 22.dp
}
