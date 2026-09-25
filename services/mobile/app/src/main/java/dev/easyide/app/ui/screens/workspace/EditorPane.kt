package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.screens.workspace.syntax.SemanticOverlay
import dev.easyide.app.ui.theme.editorColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.flow.first
import androidx.compose.ui.focus.onFocusChanged
import dev.easyide.app.data.settings.BracketMatching
import dev.easyide.app.data.settings.WordWrap
import dev.easyide.app.ui.screens.workspace.decor.EditorGutter
import dev.easyide.app.ui.screens.workspace.decor.GuidePaint
import dev.easyide.app.ui.screens.workspace.decor.GutterPaint
import dev.easyide.app.ui.screens.workspace.decor.LineStarts
import dev.easyide.app.ui.screens.workspace.decor.gutterLineNumbers
import dev.easyide.app.ui.screens.workspace.decor.indentGuides
import dev.easyide.app.ui.screens.workspace.decor.matchTextHeight
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
import dev.easyide.app.ui.screens.workspace.session.EditorScrolls
import dev.easyide.app.ui.screens.workspace.session.ExternalStateNotice
import dev.easyide.app.ui.screens.workspace.session.rememberEditorScrollStates
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dev.easyide.app.ui.screens.workspace.zoom.pinchToZoom
import dev.easyide.app.ui.screens.workspace.zoom.rememberFontZoom

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
    onSecondaryClick: ((IntOffset) -> Unit)? = null,
    /** The language server's semantic tokens for this document, painted over TextMate colouring. */
    semanticTokens: SemanticOverlay? = null,
    /** Scroll offsets of every tab, hoisted so a parked workspace comes back scrolled where it was. */
    scrolls: EditorScrolls = remember { EditorScrolls() },
    /** History, reveal and focus requests from commands and find; null for a plain editor. */
    session: EditorSession? = null,
    /** Shown when no tab is open. */
    empty: @Composable () -> Unit = {},
) {
    val colors = editorColors

    if (tab == null) {
        Box(modifier = modifier.fillMaxSize().background(colors.background)) { empty() }
        return
    }

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        tab.notice?.let { NoticeBar(it) }
        ExternalStateNotice(tab.externalState)

        when {
            tab.isMarkdown && tab.showPreview -> MarkdownPreview(tab.content)
            tab.editable -> EditableSurface(tab, onContentChanged, decorations, onGutterTap, overlay, interaction, selections, onSecondaryClick, semanticTokens, scrolls, session)
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
    onSecondaryClick: ((IntOffset) -> Unit)?,
    semanticTokens: SemanticOverlay?,
    scrolls: EditorScrolls,
    session: EditorSession?,
) {
    val colors = editorColors
    val scrollStates = rememberEditorScrollStates(tab.relativePath, scrolls)
    val verticalScroll = scrollStates.vertical
    val horizontalScroll = scrollStates.horizontal

    val totalLines = LineCount.of(tab.content)
    val config by produceState(LanguageConfig.GENERIC, tab.name) {
        value = withContext(Dispatchers.IO) { LanguageConfigs.forFile(tab.name) }
    }
    val languageId = rememberLanguageId(tab.name)
    val options = rememberEditorOptions(languageId)
    val zoom = rememberFontZoom(SettingsSchema.editorFontSize, languageId)
    val codeStyle = remember(options, zoom.size) { codeTextStyleOf(options, zoom.size) }
    val editorFocus = remember { FocusRequester() }

    // Text comes from the tab, the selection from the hoisted EditorSelections
    // (so actions can read and move it), IME composition is local - the
    // same split BasicTextField's String overload makes internally. Owning the
    // selection is what lets typing rules see and move the caret.
    val path = tab.relativePath
    val selection = selections[path].let { TextRange(it.start.coerceIn(0, tab.content.length), it.end.coerceIn(0, tab.content.length)) }
    var composition by remember(tab.relativePath) { mutableStateOf<TextRange?>(null) }
    val field = TextFieldValue(tab.content, selection, composition)
    val bracketPair = remember(tab.content, field.selection, config, options.matchBrackets) {
        when {
            options.matchBrackets == BracketMatching.NEVER || !field.selection.collapsed -> null
            else -> EditCommands.matchingBracket(tab.content, field.selection.start, config.brackets)
                ?: EditCommands.enclosingBracket(tab.content, field.selection.start, config.brackets)
                    .takeIf { options.matchBrackets == BracketMatching.ALWAYS }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val snapshot = remember(decorations) { decorations?.let(::DecorationSnapshot) }
    // Before layout and draw of this frame, so the painters see decorations already moved
    // onto the text being laid out. A no-op when the buffer is unchanged.
    SideEffect { snapshot?.sync(tab.content) }
    LaunchedEffect(snapshot) { snapshot?.follow() }
    val popupHost = remember { EditorPopupHost() }
    val measurer = rememberTextMeasurer(cacheSize = DecorationMetrics.MEASURE_CACHE)
    val gutterPainters = rememberGutterPainters()
    val selectionColors = remember(colors.accent, colors.selection) {
        TextSelectionColors(handleColor = colors.accent, backgroundColor = colors.selection)
    }
    val wrap = options.wordWrap == WordWrap.ON
    // Word wrap only: which logical line each visual line belongs to. Without wrap they are the same.
    val lineStarts = remember(tab.content, wrap) { if (wrap) LineStarts(tab.content) else null }
    val focused = remember { mutableStateOf(false) }
    val caretPhase = rememberCaretPhase(field.selection to tab.content.length, focused.value)
    val density = LocalDensity.current
    val charWidthPx = remember(codeStyle, density) { measurer.measure(CHAR_PROBE, codeStyle).size.width.toFloat().coerceAtLeast(1f) }

    // The text field is only as large as its text, so tapping beside a short
    // line or below the last line used to hit nothing and the caret never
    // moved. Giving it a minimum size of the viewport makes the whole editor
    // area a tap target, while it still grows for long lines and long files.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event -> interaction?.onPreviewKey(tab.relativePath, event) == true }
            .dismissPopupsOnEscape(popupHost)
            .pinchToZoom(zoom)
            .editorScrollbar(verticalScroll, options.scrollbar, colors.scrollbarSlider),
    ) {
        val viewportHeight = maxHeight
        val compact = EditorGutter.isCompact(maxWidth)
        val charWidth = with(density) { charWidthPx.toDp() }
        val gutterWidth = EditorGutter.width(totalLines, options.lineNumbers, charWidth, compact)
        val laneWidth = EditorGutter.laneWidth(compact)
        // Room on the right for the scrollbar; the gap before the text is part of the gutter.
        val textEnd = DecorationMetrics.scrollbarWidth
        val textMinWidth = maxWidth - gutterWidth
        val padV = DecorationMetrics.textPaddingVertical
        val padVPx = with(density) { padV.toPx() }
        val viewportPx = with(density) { viewportHeight.toPx() }
        val visiblePx = { (verticalScroll.value - padVPx)..(verticalScroll.value + viewportPx - padVPx) }

        val liveWindow = remember(verticalScroll, viewportPx, totalLines) {
            derivedStateOf { visibleLineWindow(verticalScroll.value, verticalScroll.maxValue, viewportPx, totalLines) }
        }
        val window = rememberSettledLineWindow(verticalScroll, liveWindow)
        val transformation = rememberHighlightTransformation(tab, colors, window, semanticTokens, languageId)
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
            val origin = with(density) { Offset(gutterWidth.toPx(), padVPx) }
            EditorGeometry({ layout }, { selections[path] }, verticalScroll, horizontalScroll, origin)
        }

        if (session != null) {
            val request = session.reveal
            LaunchedEffect(request) {
                if (request == null || request.path != path) return@LaunchedEffect
                revealOffsetIfHidden(request.offset, request.textLength, { layout }, verticalScroll, horizontalScroll, viewportPx)
                session.revealHandled(request)
            }
            // An undo or replace rewrote text and caret behind the IME's back: its composing region is stale.
            LaunchedEffect(session.generation(path)) { composition = null }
            val initialFocusTicks = remember { session.focusTicks }
            LaunchedEffect(session.focusTicks) { if (session.focusTicks != initialFocusTicks) editorFocus.requestFocus() }
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

        val gutterPaint = GutterPaint(
            layout = { layout },
            visiblePx = visiblePx,
            selection = { selections[path] },
            lineStarts = lineStarts,
            mode = options.lineNumbers,
            highlight = options.lineHighlight,
            colors = colors,
            numberStyle = codeStyle,
            measurer = measurer,
            rightInset = charWidth,
        )
        val guidePaint = GuidePaint({ layout }, visiblePx, TYPING_OPTIONS.indentUnit.length, charWidthPx, colors.indentGuide, DecorationMetrics.indentGuideStroke)

        Row(modifier = Modifier.fillMaxSize().verticalScroll(verticalScroll)) {
            Box(
                modifier = Modifier
                    .width(gutterWidth)
                    .matchTextHeight({ layout }, padV, viewportHeight)
                    .background(colors.gutter)
                    .gutterLineNumbers(gutterPaint, padV)
                    .gutterDecorations(paint, colors.decorations, gutterPainters, padV, laneWidth)
                    .gutterTaps({ layout }, padV, onGutterTap),
            )

            Box(modifier = if (wrap) Modifier else Modifier.horizontalScroll(horizontalScroll)) {
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
                            if (result.text != field.text) {
                                session?.onUserEdit(path, old, result)
                                onContentChanged(result.text)
                            }
                        },
                        textStyle = codeStyle.copy(color = colors.plainText),
                        // The caret is drawn by drawCaret (style, width, blink phase); the field's own is hidden.
                        cursorBrush = SolidColor(Color.Transparent),
                        visualTransformation = transformation,
                        onTextLayout = { layout = it },
                        modifier = Modifier
                            .focusRequester(editorFocus)
                            .onFocusChanged { focused.value = it.isFocused }
                            .then(if (wrap) Modifier.width(textMinWidth) else Modifier.defaultMinSize(minWidth = textMinWidth))
                            .defaultMinSize(minHeight = viewportHeight)
                            .drawCurrentLine({ layout }, { selections[path] }, lineStarts, options.lineHighlight, colors.currentLine, colors.currentLineBorder, DecorationMetrics.outlineStroke, padV)
                            .padding(end = textEnd, top = padV, bottom = padV)
                            .editorPointer(tab.relativePath, interaction) { layout }
                            .indentGuides(guidePaint, options.indentGuides)
                            .drawBracketMatch(bracketPair, { layout }, colors.bracketMatch, colors.bracketMatchBorder, DecorationMetrics.outlineStroke)
                            .textDecorations(paint, colors.decorations, measurer, codeStyle)
                            .drawCaret({ layout }, { selections[path] }, caretPhase, focused, options.cursorStyle, options.cursorWidth.dp, colors.cursor, charWidthPx)
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
 * Typing behaviour until the settings schema supplies `editor.autoClosingBrackets`,
 * `editor.autoSurround`, `editor.autoIndent` and `editor.tabSize`
 * (docs/extension-sdk/sdk-reference.md); the defaults match those keys' defaults.
 */
private val TYPING_OPTIONS = TypingOptions()


/** Explains why a file is read-only or truncated, instead of behaving oddly in silence. */
@Composable
internal fun NoticeBar(text: String) {
    val colors = editorColors
    Text(
        text = text,
        style = Kit.text.label,
        color = colors.textMuted,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.panel)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** The glyph whose advance is the editor's character cell (monospace: every glyph). */
private const val CHAR_PROBE = "0"
