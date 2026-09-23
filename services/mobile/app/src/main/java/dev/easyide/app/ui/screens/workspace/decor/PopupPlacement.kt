package dev.easyide.app.ui.screens.workspace.decor

/** An anchor's horizontal start and vertical extent in viewport pixels. */
internal data class PopupAnchor(val left: Int, val top: Int, val bottom: Int)

internal data class PopupPosition(val x: Int, val y: Int)

/**
 * Where an anchored popup goes inside the editor viewport. Pure integer math so every edge
 * case is a unit test instead of a device session.
 *
 * Rules, in order: stay [margin] inside the viewport; sit [gap] below the anchor line, or
 * above it when [PopupPlacement.place]'s `preferAbove` is set (signature help, so it does not
 * cover the completion list); flip to the other side when the preferred side is too short
 * and the other fits, else take the taller side; start at the anchor's x, shifted left
 * only as far as needed to keep the right edge inside.
 */
internal object PopupPlacement {

    /** False once the anchor line has scrolled entirely out of the viewport. */
    fun isVisible(anchor: PopupAnchor, viewportHeight: Int): Boolean =
        anchor.bottom > 0 && anchor.top < viewportHeight

    private fun spaceBelow(anchor: PopupAnchor, viewportHeight: Int, margin: Int, gap: Int) =
        (viewportHeight - margin - anchor.bottom - gap).coerceAtLeast(0)

    private fun spaceAbove(anchor: PopupAnchor, margin: Int, gap: Int) =
        (anchor.top - gap - margin).coerceAtLeast(0)

    /** The largest popup height that fits on either side; measure the content with this. */
    fun maxHeight(anchor: PopupAnchor, viewportHeight: Int, margin: Int, gap: Int): Int =
        maxOf(spaceBelow(anchor, viewportHeight, margin, gap), spaceAbove(anchor, margin, gap))

    fun maxWidth(viewportWidth: Int, margin: Int): Int = (viewportWidth - 2 * margin).coerceAtLeast(0)

    fun place(
        anchor: PopupAnchor,
        popupWidth: Int,
        popupHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        margin: Int,
        gap: Int,
        preferAbove: Boolean,
    ): PopupPosition {
        val below = spaceBelow(anchor, viewportHeight, margin, gap)
        val above = spaceAbove(anchor, margin, gap)
        val fitsBelow = popupHeight <= below
        val fitsAbove = popupHeight <= above
        val goAbove = if (preferAbove) {
            fitsAbove || (!fitsBelow && above >= below)
        } else {
            !fitsBelow && (fitsAbove || above > below)
        }
        val rawY = if (goAbove) anchor.top - gap - popupHeight else anchor.bottom + gap
        val y = rawY.coerceIn(margin, maxOf(margin, viewportHeight - margin - popupHeight))
        val x = anchor.left.coerceIn(margin, maxOf(margin, viewportWidth - margin - popupWidth))
        return PopupPosition(x, y)
    }
}
