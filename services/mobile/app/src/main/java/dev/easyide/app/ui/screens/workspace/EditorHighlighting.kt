package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import dev.easyide.app.ui.screens.workspace.syntax.SemanticOverlay
import dev.easyide.app.ui.screens.workspace.syntax.SemanticPaint
import dev.easyide.app.ui.screens.workspace.syntax.TextMateHighlighter
import dev.easyide.app.ui.screens.workspace.syntax.carryStyles
import dev.easyide.app.ui.theme.EditorColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

// Viewport-windowed, incremental syntax colouring for the editable surface (EditorPane.kt).

/** Typing pause before re-colouring, so a burst of keystrokes costs one pass. */
internal const val HIGHLIGHT_DEBOUNCE_MS = 120L

/** Lines coloured beyond the viewport, so a normal scroll never outruns the colour. */
internal const val HIGHLIGHT_OVERSCAN_LINES = 250

/**
 * Scroll re-quantised to blocks of this many lines. Without it the window would
 * change on every line crossed and restart the highlight pass.
 */
internal const val HIGHLIGHT_WINDOW_BLOCK = 250

/** Fallback window when the text has not been laid out yet and line height is unknown. */
internal const val HIGHLIGHT_INITIAL_LINES = 400

/**
 * The span of lines worth colouring right now.
 *
 * Quantised so that scrolling within a block does not restart the pass, and
 * held as a value class so it can key [produceState] by equality.
 */
internal data class LineWindow(val first: Int, val last: Int)

/**
 * The last finished colouring pass and what it was computed from, so the next
 * pass can tell a keystroke (debounce) from a tab switch or scroll (run now).
 * [source] is compared by identity: an unchanged buffer is the same instance.
 */
internal class HighlightPass(val path: String, val source: String, val styled: AnnotatedString)

/**
 * Which lines are on screen, derived from the scroll state rather than from a
 * measured text layout.
 *
 * `maxValue + viewport` is the full scrollable content height, and every line in
 * a monospace editor is the same height, so the visible range follows from the
 * scroll offset and the line count alone. An earlier version read the line
 * height out of `onTextLayout`; that value never propagated, the window stayed
 * pinned at its initial guess, and colour stopped after the first few hundred
 * lines. This has no such dependency.
 */
internal fun visibleLineWindow(scrollOffsetPx: Int, maxScrollPx: Int, viewportPx: Float, totalLines: Int): LineWindow {
    val contentPx = maxScrollPx + viewportPx
    if (totalLines <= 0 || contentPx <= 0f) return LineWindow(0, HIGHLIGHT_INITIAL_LINES)

    val lineHeightPx = contentPx / totalLines
    if (lineHeightPx <= 0f) return LineWindow(0, HIGHLIGHT_INITIAL_LINES)

    val firstVisible = (scrollOffsetPx / lineHeightPx).toInt()
    val visibleCount = (viewportPx / lineHeightPx).toInt() + 1
    val rawFirst = (firstVisible - HIGHLIGHT_OVERSCAN_LINES).coerceAtLeast(0)
    val rawLast = firstVisible + visibleCount + HIGHLIGHT_OVERSCAN_LINES

    return LineWindow(
        first = rawFirst / HIGHLIGHT_WINDOW_BLOCK * HIGHLIGHT_WINDOW_BLOCK,
        last = (rawLast / HIGHLIGHT_WINDOW_BLOCK + 1) * HIGHLIGHT_WINDOW_BLOCK,
    )
}

/**
 * The window the highlighter should colour, updated only once scrolling stops.
 *
 * The scroll offset is read inside [derivedStateOf] and a snapshot flow, never
 * directly in composition, so a scroll frame recomposes nothing unless the
 * quantised window actually moves. While a drag or fling is in progress the
 * last window is kept: re-keying mid-fling cancelled and restarted the pass on
 * every block crossed, burning the CPU the fling needed. The wider overscan
 * covers the lines a short fling reveals before the pass catches up.
 */
@Composable
internal fun rememberSettledLineWindow(scroll: ScrollState, live: State<LineWindow>): LineWindow {
    // Seeded without a read observation, or composition would subscribe to
    // every window change and the point of settling would be lost.
    val settled = remember { mutableStateOf(Snapshot.withoutReadObservation { live.value }) }
    LaunchedEffect(live) {
        snapshotFlow { if (scroll.isScrollInProgress) null else live.value }
            .filterNotNull()
            .collect { settled.value = it }
    }
    return settled.value
}

/**
 * Colouring runs off the composition thread, incrementally, over the visible
 * window only.
 *
 * Three things keep this off the critical path, and all three are needed:
 * the pass runs on [Dispatchers.Default] after a debounce; the tokenizer keeps
 * per-line state so an edit only re-scans from the line that changed; and only
 * the lines in [window] get spans, so the styled-span count stays flat however
 * long the file is.
 */
@Composable
internal fun rememberHighlightTransformation(
    tab: EditorTab,
    colors: EditorColors,
    window: LineWindow,
    semantic: SemanticOverlay?,
    languageId: String?,
): VisualTransformation {
    val plain = remember(tab.content) { AnnotatedString(tab.content) }
    val pass by produceState(
        HighlightPass(tab.relativePath, tab.content, plain),
        tab.content, tab.relativePath, tab.highlightingEnabled, colors, window, semantic, languageId,
    ) {
        if (!tab.highlightingEnabled) {
            value = HighlightPass(tab.relativePath, tab.content, plain)
            return@produceState
        }
        // Only a burst of keystrokes is worth waiting out. Opening a file,
        // switching tabs, scrolling to a new window or a theme change must
        // colour immediately; debouncing those was a visible 120 ms+ of grey.
        val edited = value.path == tab.relativePath && value.source !== tab.content
        if (edited) delay(HIGHLIGHT_DEBOUNCE_MS)
        val styled = withContext(Dispatchers.Default) {
            val context = coroutineContext
            TextMateHighlighter.highlight(
                key = tab.relativePath,
                source = tab.content,
                fileName = tab.name,
                colors = colors.syntax,
                firstLine = window.first,
                lastLine = window.last,
                checkCancelled = { context.ensureActive() },
                semantic = SemanticPaint.of(semantic, tab.content, colors, languageId),
            )
        }
        value = HighlightPass(tab.relativePath, tab.content, styled)
    }
    val highlighted = pass.styled
    return remember(highlighted) {
        VisualTransformation { current ->
            // A pass that finished against an older buffer cannot be applied as is:
            // VisualTransformation requires the text to match the field exactly. Its
            // colours are carried over the edit until the next pass lands, so typing
            // does not flash the whole file grey between keystroke and pass.
            val styled = if (highlighted.text == current.text) highlighted else carryStyles(highlighted, current.text)
            TransformedText(styled, OffsetMapping.Identity)
        }
    }
}
