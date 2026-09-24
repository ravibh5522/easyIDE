package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/** Revealed lines land this far down the viewport, so the context above stays visible. */
private const val REVEAL_VIEWPORT_FRACTION = 1f / 3

/**
 * Scrolls so [offset] sits a third of the way down the viewport (the reading position
 * navigation lands on), waiting for the layout of the text the offset refers to.
 */
internal suspend fun revealOffset(
    offset: Int,
    textLength: Int,
    layout: () -> TextLayoutResult?,
    vertical: ScrollState,
    horizontal: ScrollState,
    viewportPx: Float,
) {
    val result = layoutFor(textLength, layout) ?: return
    val caret = result.getCursorRect(offset.coerceIn(0, textLength))
    vertical.animateScrollTo((caret.top - viewportPx * REVEAL_VIEWPORT_FRACTION).roundToInt().coerceAtLeast(0))
    if (caret.left < horizontal.value || caret.left > horizontal.value + horizontal.viewportSize) {
        horizontal.animateScrollTo((caret.left - horizontal.viewportSize * REVEAL_VIEWPORT_FRACTION).roundToInt().coerceAtLeast(0))
    }
}

/**
 * [revealOffset], but only when the caret is outside the viewport (a one-line margin counts as
 * outside). Find and undo use it: stepping through matches on screen must not scroll at all.
 */
internal suspend fun revealOffsetIfHidden(
    offset: Int,
    textLength: Int,
    layout: () -> TextLayoutResult?,
    vertical: ScrollState,
    horizontal: ScrollState,
    viewportPx: Float,
) {
    val result = layoutFor(textLength, layout) ?: return
    val caret = result.getCursorRect(offset.coerceIn(0, textLength))
    val top = vertical.value
    val hiddenVertically = caret.top < top + caret.height || caret.bottom > top + viewportPx - caret.height
    val hiddenHorizontally = caret.left < horizontal.value || caret.left > horizontal.value + horizontal.viewportSize
    if (hiddenVertically || hiddenHorizontally) revealOffset(offset, textLength, layout, vertical, horizontal, viewportPx)
}

private suspend fun layoutFor(textLength: Int, layout: () -> TextLayoutResult?): TextLayoutResult? =
    snapshotFlow { layout() }.first { it != null && it.layoutInput.text.length == textLength }
