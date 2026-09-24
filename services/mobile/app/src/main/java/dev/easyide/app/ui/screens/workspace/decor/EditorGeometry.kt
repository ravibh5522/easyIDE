package dev.easyide.app.ui.screens.workspace.decor

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange

/**
 * Maps document offsets to rectangles in the editor viewport, for anything drawn over the
 * text that must follow it: hover cards, completion, signature help, the lightbulb menu.
 *
 * Viewport coordinates are pixels relative to the editor's visible area (gutter included,
 * top-left = 0,0), which is exactly the space an [EditorPopup] is laid out in. Every read
 * goes through snapshot state (layout, scroll, selection), so calling these from a layout
 * or draw lambda re-places the caller when the text scrolls or the caret moves, without
 * recomposing it.
 *
 * This is the seam decision 0018 keeps stable across editor engines: a line-virtualised
 * editor implements the same questions from its own line layouts.
 */
@Stable
class EditorGeometry internal constructor(
    private val layout: () -> TextLayoutResult?,
    private val selection: () -> TextRange,
    private val verticalScroll: ScrollState,
    private val horizontalScroll: ScrollState,
    /** Where the text layout's (0, 0) sits in the viewport when both scrolls are at 0. */
    private val textOrigin: Offset,
) {
    /** The caret offset: the moving end of the selection, in UTF-16 code units. */
    val caret: Int get() = selection().end

    /** The current selection, `start > end` when made backwards. */
    val currentSelection: TextRange get() = selection()

    /** The caret's rectangle at [offset] (zero-width, one line tall), or null before first layout. */
    fun caretRect(offset: Int = caret): Rect? {
        val result = layout() ?: return null
        return toViewport(result.getCursorRect(offset.coerceIn(0, result.layoutInput.text.length)))
    }

    /**
     * The box covering `[start, end)` on the line where [start] is, for anchoring to a word
     * (hover). Clipped to that line so a multi-line range anchors to its first line.
     */
    fun rangeRect(start: Int, end: Int): Rect? {
        val result = layout() ?: return null
        val length = result.layoutInput.text.length
        val from = start.coerceIn(0, length)
        val line = result.getLineForOffset(from)
        val to = end.coerceIn(from, result.getLineEnd(line))
        val left = result.getHorizontalPosition(from, usePrimaryDirection = true)
        val right = if (to > from) result.getHorizontalPosition(to, usePrimaryDirection = true) else left
        return toViewport(Rect(minOf(left, right), result.getLineTop(line), maxOf(left, right), result.getLineBottom(line)))
    }

    /** The full-width box of 0-based [line], or null when out of range or not laid out. */
    fun lineRect(line: Int): Rect? {
        val result = layout() ?: return null
        if (line !in 0 until result.lineCount) return null
        return toViewport(Rect(0f, result.getLineTop(line), result.size.width.toFloat(), result.getLineBottom(line)))
    }

    /** The 0-based line containing [offset], or null before first layout. */
    fun lineOf(offset: Int): Int? {
        val result = layout() ?: return null
        return result.getLineForOffset(offset.coerceIn(0, result.layoutInput.text.length))
    }

    private fun toViewport(rect: Rect): Rect = rect.translate(
        textOrigin.x - horizontalScroll.value,
        textOrigin.y - verticalScroll.value,
    )
}

/**
 * The decoration snapshot the painters read, bridged from [DecorationModel.state] into
 * Compose snapshot state so a change invalidates the draw phase that reads it - and nothing
 * else, because composition never reads [value].
 *
 * [sync] publishes synchronously: an edit moves the decorations in the same frame that lays
 * out the new text. Waiting for the flow collector instead left one frame per keystroke in
 * which the snapshot's text no longer matched the layout, and the painters skip such frames,
 * so squiggles would have blinked while typing.
 */
@Stable
internal class DecorationSnapshot(private val model: DecorationModel) {
    private val current = mutableStateOf(model.state.value)

    val value: DecorationSet get() = current.value

    fun sync(text: String) {
        model.syncText(text)
        current.value = model.state.value
    }

    /** Follows writes from producers (language servers, find) until cancelled. */
    suspend fun follow() {
        model.state.collect { current.value = it }
    }
}
