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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
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
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.ui.foundation.LocalSettings
import dev.easyide.app.ui.screens.workspace.syntax.TextMateHighlighter
import dev.easyide.app.ui.theme.EasyIdeFonts
import dev.easyide.app.ui.theme.EditorColors
import dev.easyide.app.ui.theme.editorColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.easyide.app.ui.screens.workspace.edit.EditCommands
import dev.easyide.app.ui.screens.workspace.edit.LanguageConfig
import dev.easyide.app.ui.screens.workspace.edit.TextState
import dev.easyide.app.ui.screens.workspace.edit.TypingOptions
import dev.easyide.app.ui.screens.workspace.edit.TypingRules
import dev.easyide.app.ui.screens.workspace.syntax.LanguageConfigs
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import dev.easyide.app.ui.screens.workspace.decor.DecorationInputs
import dev.easyide.app.ui.screens.workspace.decor.DecorationMetrics
import dev.easyide.app.ui.screens.workspace.decor.DecorationModel
import dev.easyide.app.ui.screens.workspace.decor.DecorationSnapshot
import dev.easyide.app.ui.screens.workspace.decor.EditorGeometry
import dev.easyide.app.ui.screens.workspace.decor.EditorPopup
import dev.easyide.app.ui.screens.workspace.decor.EditorPopupHost
import dev.easyide.app.ui.screens.workspace.decor.LocalEditorPopupHost
import dev.easyide.app.ui.screens.workspace.decor.dismissPopupsOnEscape
import dev.easyide.app.ui.screens.workspace.decor.gutterDecorations
import dev.easyide.app.ui.screens.workspace.decor.gutterTaps
import dev.easyide.app.ui.screens.workspace.decor.rememberGutterPainters
import dev.easyide.app.ui.screens.workspace.decor.textDecorations

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
    /** The open document's decorations; painted on the editable surface only. */
    decorations: DecorationModel? = null,
    /** A tap on a gutter line (0-based), for the lightbulb and code lens glyphs. */
    onGutterTap: ((line: Int) -> Unit)? = null,
    /** Content laid over the editable text viewport, typically [EditorPopup]s. */
    overlay: @Composable (EditorGeometry) -> Unit = {},
    /** Language features' view of input and caret; null for a plain editor. */
    interaction: EditorInteraction? = null,
    /** Carets of every tab, hoisted so extension actions can read and move them. */
    selections: EditorSelections = remember { EditorSelections() },
    /** A secondary click (mouse right button, stylus button) on the text: the `editor/context` menu. */
    onSecondaryClick: (() -> Unit)? = null,
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
            tab.editable -> EditableSurface(tab, onContentChanged, decorations, onGutterTap, overlay, interaction, selections, onSecondaryClick)
            else -> ReadOnlySurface(tab, interaction)
        }
    }
}

@Composable
private fun EditableSurface(
    tab: EditorTab,
    onContentChanged: (String) -> Unit,
    decorations: DecorationModel?,
    onGutterTap: ((line: Int) -> Unit)?,
    overlay: @Composable (EditorGeometry) -> Unit,
    interaction: EditorInteraction?,
    selections: EditorSelections,
    onSecondaryClick: (() -> Unit)?,
) {
    val colors = editorColors
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    val totalLines = LineCount.of(tab.content)
    val lineNumbers = remember(totalLines) { (1..totalLines).joinToString("\n") }
    val config by produceState(LanguageConfig.GENERIC, tab.name) {
        value = withContext(Dispatchers.IO) { LanguageConfigs.forFile(tab.name) }
    }
    val languageId = rememberLanguageId(tab.name)

    // Text comes from the tab, the selection from the hoisted EditorSelections
    // (so actions can read and move it), IME composition is local - the
    // same split BasicTextField's String overload makes internally. Owning the
    // selection is what lets typing rules see and move the caret.
    val path = tab.relativePath
    val selection = selections[path].let { TextRange(it.start.coerceIn(0, tab.content.length), it.end.coerceIn(0, tab.content.length)) }
    var composition by remember(tab.relativePath) { mutableStateOf<TextRange?>(null) }
    val field = TextFieldValue(tab.content, selection, composition)
    val bracketPair = remember(tab.content, field.selection, config) {
        if (field.selection.collapsed) EditCommands.matchingBracket(tab.content, field.selection.start, config.brackets)
        else null
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Derived, so the gutter recomposes when the caret changes line, not on every caret move.
    // Reads the hoisted state inside the derivation: the local `selection` is a per-composition
    // value, and capturing it here would freeze the gutter on the first caret position.
    val caretLine by remember(tab.relativePath) {
        derivedStateOf { layout?.let { it.getLineForOffset(selections[path].start.coerceIn(0, it.layoutInput.text.length)) } ?: 0 }
    }
    val numbered = remember(lineNumbers, caretLine, colors.gutterActiveText) {
        gutterNumbers(lineNumbers, caretLine, colors.gutterActiveText)
    }
    val snapshot = remember(decorations) { decorations?.let(::DecorationSnapshot) }
    // Before layout and draw of this frame, so the painters see decorations already moved
    // onto the text being laid out. A no-op when the buffer is unchanged.
    SideEffect { snapshot?.sync(tab.content) }
    LaunchedEffect(snapshot) { snapshot?.follow() }
    val popupHost = remember { EditorPopupHost() }
    val measurer = rememberTextMeasurer(cacheSize = DecorationMetrics.GHOST_TEXT_MEASURE_CACHE)
    val gutterPainters = rememberGutterPainters()
    val gutterWidth = GUTTER_WIDTH_DP.dp + DecorationMetrics.gutterLaneWidth
    val selectionColors = remember(colors.cursor, colors.selection) {
        TextSelectionColors(handleColor = colors.cursor, backgroundColor = colors.selection)
    }

    // The text field is only as large as its text, so tapping beside a short
    // line or below the last line used to hit nothing and the caret never
    // moved. Giving it a minimum size of the viewport makes the whole editor
    // area a tap target, while it still grows for long lines and long files.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event -> interaction?.onPreviewKey(tab.relativePath, event) == true }
            .dismissPopupsOnEscape(popupHost),
    ) {
        val viewportHeight = maxHeight
        val textMinWidth = maxWidth - gutterWidth
        val density = LocalDensity.current
        val viewportPx = with(density) { viewportHeight.toPx() }

        val liveWindow = remember(verticalScroll, viewportPx, totalLines) {
            derivedStateOf { visibleLineWindow(verticalScroll.value, verticalScroll.maxValue, viewportPx, totalLines) }
        }
        val window = rememberSettledLineWindow(verticalScroll, liveWindow)
        val transformation = rememberHighlightTransformation(tab, colors, window)
        // Painting follows the live window, not the settled one: it is cheap, and a fling
        // must not outrun the squiggles the way it may briefly outrun colouring.
        val paint = remember(snapshot, liveWindow) {
            DecorationInputs(
                decorations = { snapshot?.value },
                layout = { layout },
                visibleLines = { liveWindow.value.let { it.first..it.last } },
            )
        }
        // Keyed by path too: the caret lambda reads this tab's entry of the hoisted selections.
        val geometry = remember(tab.relativePath, verticalScroll, horizontalScroll, density, gutterWidth) {
            val origin = with(density) {
                Offset((gutterWidth + TEXT_PADDING_H_DP.dp).toPx(), TEXT_PADDING_V_DP.dp.toPx())
            }
            EditorGeometry({ layout }, { selections[path] }, verticalScroll, horizontalScroll, origin)
        }

        if (interaction != null) {
            LaunchedEffect(interaction, path, tab.content, selection) { interaction.onCaretChanged(path, tab.content, selection) }
            LaunchedEffect(interaction, path, liveWindow) {
                snapshotFlow { liveWindow.value }.collect { interaction.onVisibleLinesChanged(path, it.first, it.last) }
            }
            val request by interaction.selectionRequests.collectAsState()
            val pending = request?.takeIf { it.path == path && it.text == tab.content }
            LaunchedEffect(pending?.id) {
                val r = pending ?: return@LaunchedEffect
                selections[path] = TextRange(r.start, r.end)
                composition = null
                interaction.onSelectionRequestApplied(r)
                if (r.reveal) revealOffset(r.start, tab.content.length, { layout }, verticalScroll, horizontalScroll, viewportPx)
            }
        }

        Row(modifier = Modifier.fillMaxSize().verticalScroll(verticalScroll)) {
            Text(
                text = numbered,
                style = codeTextStyle(languageId).copy(color = colors.gutterText),
                textAlign = TextAlign.End,
                modifier = Modifier
                    .width(gutterWidth)
                    .background(colors.gutter)
                    .gutterDecorations(paint, colors.decorations, gutterPainters, TEXT_PADDING_V_DP.dp)
                    .gutterTaps({ layout }, TEXT_PADDING_V_DP.dp, onGutterTap)
                    .padding(end = TEXT_PADDING_H_DP.dp, top = TEXT_PADDING_V_DP.dp, bottom = TEXT_PADDING_V_DP.dp),
            )

            Box(modifier = Modifier.horizontalScroll(horizontalScroll)) {
                CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                    BasicTextField(
                        value = field,
                        onValueChange = { next ->
                            val old = TextState(field.text, field.selection.start, field.selection.end)
                            val typed = TextState(next.text, next.selection.start, next.selection.end)
                            val ruled = TypingRules.onChange(old, typed, config, TYPING_OPTIONS)
                            val result = interaction?.transformEdit(tab.relativePath, old, ruled) ?: ruled
                            if (result === typed) {
                                selections[path] = next.selection
                                composition = next.composition
                            } else {
                                // A rewritten edit no longer lines up with the IME's composing region.
                                selections[path] = TextRange(result.selectionStart, result.selectionEnd)
                                composition = null
                            }
                            if (result.text != field.text) onContentChanged(result.text)
                        },
                        textStyle = codeTextStyle(languageId).copy(color = colors.plainText),
                        cursorBrush = SolidColor(colors.cursor),
                        visualTransformation = transformation,
                        onTextLayout = { layout = it },
                        modifier = Modifier
                            .defaultMinSize(minWidth = textMinWidth, minHeight = viewportHeight)
                            .drawCurrentLine({ layout }, { selection.start }, colors.currentLine, TEXT_PADDING_V_DP.dp)
                            .padding(horizontal = TEXT_PADDING_H_DP.dp, vertical = TEXT_PADDING_V_DP.dp)
                            .editorPointer(tab.relativePath, interaction) { layout }
                            .drawBracketMatch(bracketPair, { layout }, colors.bracketMatch)
                            .textDecorations(paint, colors.decorations, measurer, codeTextStyle(languageId))
                            .secondaryClicks(onSecondaryClick)
                            // Own layer for the text itself: a decoration redraw then replays
                            // the recorded paragraph instead of drawing it again.
                            .graphicsLayer(),
                    )
                }
            }
        }

        CompositionLocalProvider(LocalEditorPopupHost provides popupHost) {
            overlay(geometry)
        }
    }
}

/**
 * Scrolls so [offset] sits a third of the way down the viewport (the reading position
 * navigation lands on), waiting for the layout of the text the offset refers to.
 */
private suspend fun revealOffset(
    offset: Int,
    textLength: Int,
    layout: () -> TextLayoutResult?,
    vertical: ScrollState,
    horizontal: ScrollState,
    viewportPx: Float,
) {
    val result = snapshotFlow { layout() }.first { it != null && it.layoutInput.text.length == textLength } ?: return
    val caret = result.getCursorRect(offset.coerceIn(0, textLength))
    vertical.animateScrollTo((caret.top - viewportPx * REVEAL_VIEWPORT_FRACTION).roundToInt().coerceAtLeast(0))
    if (caret.left < horizontal.value || caret.left > horizontal.value + horizontal.viewportSize) {
        horizontal.animateScrollTo((caret.left - horizontal.viewportSize * REVEAL_VIEWPORT_FRACTION).roundToInt().coerceAtLeast(0))
    }
}

/**
 * Boxes the bracket pair at the caret. Drawn from the existing text layout
 * rather than as spans in the visual transformation: a span change would make
 * the field re-lay-out the whole buffer on every caret move.
 */
private fun Modifier.drawBracketMatch(
    pair: Pair<Int, Int>?,
    layout: () -> TextLayoutResult?,
    color: Color,
): Modifier = if (pair == null) this else drawBehind {
    val result = layout() ?: return@drawBehind
    val length = result.layoutInput.text.length
    for (offset in intArrayOf(pair.first, pair.second)) {
        if (offset >= length) continue
        val box = result.getBoundingBox(offset)
        drawRect(color, topLeft = box.topLeft, size = box.size)
    }
}

/** Typing pause before re-colouring, so a burst of keystrokes costs one pass. */
private const val HIGHLIGHT_DEBOUNCE_MS = 120L

/** Lines coloured beyond the viewport, so a normal scroll never outruns the colour. */
private const val HIGHLIGHT_OVERSCAN_LINES = 250

/**
 * Scroll re-quantised to blocks of this many lines. Without it the window would
 * change on every line crossed and restart the highlight pass.
 */
private const val HIGHLIGHT_WINDOW_BLOCK = 250

/** Fallback window when the text has not been laid out yet and line height is unknown. */
private const val HIGHLIGHT_INITIAL_LINES = 400

/**
 * Typing behaviour until the settings schema supplies `editor.autoClosingBrackets`,
 * `editor.autoSurround`, `editor.autoIndent` and `editor.tabSize`
 * (docs/extension-sdk/sdk-reference.md); the defaults match those keys' defaults.
 */
private val TYPING_OPTIONS = TypingOptions()

/**
 * The span of lines worth colouring right now.
 *
 * Quantised so that scrolling within a block does not restart the pass, and
 * held as a value class so it can key [produceState] by equality.
 */
private data class LineWindow(val first: Int, val last: Int)

/**
 * The last finished colouring pass and what it was computed from, so the next
 * pass can tell a keystroke (debounce) from a tab switch or scroll (run now).
 * [source] is compared by identity: an unchanged buffer is the same instance.
 */
private class HighlightPass(val path: String, val source: String, val styled: AnnotatedString)

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
private fun visibleLineWindow(scrollOffsetPx: Int, maxScrollPx: Int, viewportPx: Float, totalLines: Int): LineWindow {
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
private fun rememberSettledLineWindow(scroll: ScrollState, live: State<LineWindow>): LineWindow {
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
private fun rememberHighlightTransformation(
    tab: EditorTab,
    colors: EditorColors,
    window: LineWindow,
): VisualTransformation {
    val plain = remember(tab.content) { AnnotatedString(tab.content) }
    val pass by produceState(
        HighlightPass(tab.relativePath, tab.content, plain),
        tab.content, tab.relativePath, tab.highlightingEnabled, colors, window,
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
            )
        }
        value = HighlightPass(tab.relativePath, tab.content, styled)
    }
    val highlighted = pass.styled
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
        color = colors.textMuted,
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
                color = colors.textMuted,
            )
            Text(
                text = "Pick a file from the explorer to start editing",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

/**
 * One definition so the gutter and buffer share a line height and never drift.
 * [languageId] applies `[lang]` settings blocks for the open document.
 */
@Composable
fun codeTextStyle(languageId: String? = null): TextStyle {
    val settings = LocalSettings.current
    val fontSize = settings.get(SettingsSchema.editorFontSize, languageId)
    return TextStyle(
        fontFamily = EasyIdeFonts.mono,
        fontSize = fontSize.sp,
        // Both are independent settings; a line shorter than its glyphs would
        // overlap neighbouring lines, so the font size is the floor.
        lineHeight = settings.get(SettingsSchema.editorLineHeight, languageId).coerceAtLeast(fontSize).sp,
    )
}

/** The document's language id, looked up off the main thread (the grammar index may still be loading). */
@Composable
internal fun rememberLanguageId(fileName: String): String? {
    val id by produceState<String?>(null, fileName) {
        value = withContext(Dispatchers.IO) { LanguageConfigs.languageIdFor(fileName) }
    }
    return id
}

internal const val GUTTER_WIDTH_DP = 52

/** Revealed lines land this far down the viewport, so the context above stays visible. */
private const val REVEAL_VIEWPORT_FRACTION = 1f / 3

/**
 * Inset of the text inside the editable surface. Named because three things must agree on
 * it: the text field, the gutter (so line numbers and glyphs sit on their lines) and
 * [EditorGeometry]'s viewport mapping.
 */
private const val TEXT_PADDING_H_DP = 8
private const val TEXT_PADDING_V_DP = 4



