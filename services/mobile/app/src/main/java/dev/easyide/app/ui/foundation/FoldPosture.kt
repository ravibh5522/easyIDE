package dev.easyide.app.ui.foundation

/** Which way the hinge line runs: a vertical hinge makes two side-by-side pages, a horizontal one two stacked. */
enum class HingeAxis { VERTICAL, HORIZONTAL }

/**
 * The fold of a foldable (or a dual-screen device) as the layout needs it, decoupled from
 * androidx.window so the arrangement rules are plain JVM logic. Bounds are the hinge's
 * rectangle in window pixels.
 *
 * [halfOpened] is a device held part-way; [separating] means the fold divides the window
 * into two logical areas that should not be spanned by one control (a dual-screen device,
 * or a hinge that occludes content). [occludes] is true when the hinge hides pixels, so the
 * layout must leave a gap rather than draw under it.
 */
data class FoldPosture(
    val axis: HingeAxis,
    val halfOpened: Boolean,
    val separating: Boolean,
    val occludes: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    /** Hinge start and end along the axis the pages are split on. */
    val spanStart: Int get() = if (axis == HingeAxis.VERTICAL) left else top
    val spanEnd: Int get() = if (axis == HingeAxis.VERTICAL) right else bottom

    /** Two pages side by side, like an open book. */
    val isBook: Boolean get() = axis == HingeAxis.VERTICAL && (halfOpened || separating)

    /** Two pages stacked: screen above, screen below (a laptop, or a phone propped on its fold). */
    val isTabletop: Boolean get() = axis == HingeAxis.HORIZONTAL && (halfOpened || separating)
}
