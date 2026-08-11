package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.easyide.app.ui.screens.workspace.syntax.TextMateHighlighter
import dev.easyide.app.ui.theme.EditorColors
import dev.easyide.app.ui.theme.editorColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalDensity

/**
 * The code surface. Three modes, picked by what the tab holds:
 *
 *  - markdown with preview on -> [MarkdownPreview];
 *  - editable text -> a text field with a gutter and memoised highlighting;
 *  - read-only text (large file or binary dump) -> a **virtualised** line list.
 *
 * That last one matters: putting a multi-megabyte string into a single text
 * field stalls layout on the UI thread, which is what made large files appear
 * to crash the app. A LazyColumn only measures the visible lines.
 */
@Composable
fun EditorPane(
    tab: EditorTab?,
    onContentChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = editorColors

    if (tab == null) {
        EmptyEditor(modifier)
        return
    }

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        tab.notice?.let { NoticeBar(it) }

        when {
            tab.isMarkdown && tab.showPreview -> MarkdownPreview(tab.content)
            tab.editable -> EditableSurface(tab, onContentChanged)
            else -> ReadOnlySurface(tab)
        }
    }
}

@Composable
private fun EditableSurface(tab: EditorTab, onContentChanged: (String) -> Unit) {
    val colors = editorColors
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    val totalLines = remember(tab.content) { tab.content.count { it == '\n' } + 1 }
    val lineNumbers = remember(totalLines) { (1..totalLines).joinToString("\n") }

    // The text field is only as large as its text, so tapping beside a short
    // line or below the last line used to hit nothing and the caret never
    // moved. Giving it a minimum size of the viewport makes the whole editor
    // area a tap target, while it still grows for long lines and long files.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        val textMinWidth = maxWidth - GUTTER_WIDTH_DP.dp
        val viewportPx = with(LocalDensity.current) { viewportHeight.toPx() }

        val window = rememberVisibleLineWindow(
            scrollOffsetPx = verticalScroll.value,
            maxScrollPx = verticalScroll.maxValue,
            viewportPx = viewportPx,
            totalLines = totalLines,
        )
        val transformation = rememberHighlightTransformation(tab, colors, window)

        Row(modifier = Modifier.fillMaxSize().verticalScroll(verticalScroll)) {
            Text(
                text = lineNumbers,
                style = codeTextStyle().copy(color = colors.gutterText),
                textAlign = TextAlign.End,
                modifier = Modifier
                    .width(GUTTER_WIDTH_DP.dp)
                    .background(colors.gutter)
                    .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
            )

            Box(modifier = Modifier.horizontalScroll(horizontalScroll)) {
                BasicTextField(
                    value = tab.content,
                    onValueChange = onContentChanged,
                    textStyle = codeTextStyle().copy(color = colors.plainText),
                    cursorBrush = SolidColor(colors.plainText),
                    visualTransformation = transformation,
                    modifier = Modifier
                        .defaultMinSize(minWidth = textMinWidth, minHeight = viewportHeight)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** Virtualised viewer: only the visible lines are ever measured. */
@Composable
private fun ReadOnlySurface(tab: EditorTab) {
    val colors = editorColors
    val horizontalScroll = rememberScrollState()
    val lines = remember(tab.content) { tab.content.lines() }
    val gutterWidth = remember(lines.size) {
        // Widen the gutter for files with many lines so numbers never clip.
        (GUTTER_WIDTH_DP + (lines.size.toString().length - 2).coerceAtLeast(0) * GUTTER_DIGIT_DP).dp
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(lines) { index, line ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${index + 1}",
                    style = codeTextStyle().copy(color = colors.gutterText),
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .width(gutterWidth)
                        .background(colors.gutter)
                        .padding(end = 8.dp),
                )
                Text(
                    text = line,
                    style = codeTextStyle().copy(color = colors.plainText),
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(horizontalScroll)
                        .padding(start = 8.dp),
                )
            }
        }
    }
}

/** Typing pause before re-colouring, so a burst of keystrokes costs one pass. */
private const val HIGHLIGHT_DEBOUNCE_MS = 120L

/** Lines coloured beyond the viewport, so a normal scroll never outruns the colour. */
private const val HIGHLIGHT_OVERSCAN_LINES = 150

/**
 * Scroll re-quantised to blocks of this many lines. Without it the window would
 * change on every line crossed and re-key the highlight pass mid-fling.
 */
private const val HIGHLIGHT_WINDOW_BLOCK = 250

/** Fallback window when the text has not been laid out yet and line height is unknown. */
private const val HIGHLIGHT_INITIAL_LINES = 400

/**
 * The span of lines worth colouring right now.
 *
 * Quantised so that scrolling within a block does not restart the pass, and
 * held as a value class so it can key [produceState] by equality.
 */
private data class LineWindow(val first: Int, val last: Int)

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
@Composable
private fun rememberVisibleLineWindow(
    scrollOffsetPx: Int,
    maxScrollPx: Int,
    viewportPx: Float,
    totalLines: Int,
): LineWindow = remember(scrollOffsetPx, maxScrollPx, viewportPx, totalLines) {
    val contentPx = maxScrollPx + viewportPx
    if (totalLines <= 0 || contentPx <= 0f) return@remember LineWindow(0, HIGHLIGHT_INITIAL_LINES)

    val lineHeightPx = contentPx / totalLines
    if (lineHeightPx <= 0f) return@remember LineWindow(0, HIGHLIGHT_INITIAL_LINES)

    val firstVisible = (scrollOffsetPx / lineHeightPx).toInt()
    val visibleCount = (viewportPx / lineHeightPx).toInt() + 1
    val rawFirst = (firstVisible - HIGHLIGHT_OVERSCAN_LINES).coerceAtLeast(0)
    val rawLast = firstVisible + visibleCount + HIGHLIGHT_OVERSCAN_LINES

    LineWindow(
        first = rawFirst / HIGHLIGHT_WINDOW_BLOCK * HIGHLIGHT_WINDOW_BLOCK,
        last = (rawLast / HIGHLIGHT_WINDOW_BLOCK + 1) * HIGHLIGHT_WINDOW_BLOCK,
    )
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
private fun rememberHighlightTransformation(
    tab: EditorTab,
    colors: EditorColors,
    window: LineWindow,
): VisualTransformation {
    val plain = remember(tab.content) { AnnotatedString(tab.content) }
    val highlighted by produceState(
        plain, tab.content, tab.relativePath, tab.highlightingEnabled, colors, window,
    ) {
        if (!tab.highlightingEnabled) {
            value = plain
            return@produceState
        }
        delay(HIGHLIGHT_DEBOUNCE_MS)
        value = withContext(Dispatchers.Default) {
            TextMateHighlighter.highlight(
                key = tab.relativePath,
                source = tab.content,
                fileName = tab.name,
                colors = colors.syntax,
                firstLine = window.first,
                lastLine = window.last,
            )
        }
    }
    return remember(highlighted) {
        VisualTransformation { current ->
            // A pass that finished against an older buffer must not be applied:
            // VisualTransformation requires the text to match the field exactly.
            val styled = if (highlighted.text == current.text) highlighted else current
            TransformedText(styled, OffsetMapping.Identity)
        }
    }
}

/** Explains why a file is read-only or truncated, instead of behaving oddly in silence. */
@Composable
private fun NoticeBar(text: String) {
    val colors = editorColors
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = colors.gutterText,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.panel)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun EmptyEditor(modifier: Modifier) {
    val colors = editorColors
    Box(
        modifier = modifier.fillMaxSize().background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "No file open",
                style = MaterialTheme.typography.titleSmall,
                color = colors.gutterText,
            )
            Text(
                text = "Pick a file from the explorer to start editing",
                style = MaterialTheme.typography.bodySmall,
                color = colors.gutterText,
            )
        }
    }
}

/** One definition so the gutter and buffer share a line height and never drift. */
@Composable
fun codeTextStyle(): TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = CODE_FONT_SP.sp,
    lineHeight = CODE_LINE_HEIGHT_SP.sp,
)

private const val GUTTER_WIDTH_DP = 52
private const val GUTTER_DIGIT_DP = 8
private const val CODE_FONT_SP = 13
private const val CODE_LINE_HEIGHT_SP = 20



