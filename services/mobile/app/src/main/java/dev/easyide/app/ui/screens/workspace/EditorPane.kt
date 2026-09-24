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
    onSecondaryClick: (() -> Unit)? = null,
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

        when {
            tab.isMarkdown && tab.showPreview -> MarkdownPreview(tab.content)
            tab.editable -> EditableSurface(tab, onContentChanged, decorations, onGutterTap, overlay, interaction, selections, onSecondaryClick, session)
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
    session: EditorSession?,
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
    val zoom = rememberFontZoom(SettingsSchema.editorFontSize, languageId)
    val codeStyle = codeTextStyle(languageId, zoom.size)
    val editorFocus = remember { FocusRequester() }

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
            .dismissPopupsOnEscape(popupHost)
            .pinchToZoom(zoom),
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

        Row(modifier = Modifier.fillMaxSize().verticalScroll(verticalScroll)) {
            Text(
                text = numbered,
                style = codeStyle.copy(color = colors.gutterText),
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
                            if (result.text != field.text) {
                                session?.onUserEdit(path, old, result)
                                onContentChanged(result.text)
                            }
                        },
                        textStyle = codeStyle.copy(color = colors.plainText),
                        cursorBrush = SolidColor(colors.cursor),
                        visualTransformation = transformation,
                        onTextLayout = { layout = it },
                        modifier = Modifier
                            .focusRequester(editorFocus)
                            .defaultMinSize(minWidth = textMinWidth, minHeight = viewportHeight)
                            .drawCurrentLine({ layout }, { selection.start }, colors.currentLine, TEXT_PADDING_V_DP.dp)
                            .padding(horizontal = TEXT_PADDING_H_DP.dp, vertical = TEXT_PADDING_V_DP.dp)
                            .editorPointer(tab.relativePath, interaction) { layout }
                            .drawBracketMatch(bracketPair, { layout }, colors.bracketMatch)
                            .textDecorations(paint, colors.decorations, measurer, codeStyle)
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


/**
 * Typing behaviour until the settings schema supplies `editor.autoClosingBrackets`,
 * `editor.autoSurround`, `editor.autoIndent` and `editor.tabSize`
 * (docs/extension-sdk/sdk-reference.md); the defaults match those keys' defaults.
 */
private val TYPING_OPTIONS = TypingOptions()


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

/**
 * One definition so the gutter and buffer share a line height and never drift.
 * [languageId] applies `[lang]` settings blocks for the open document; [fontSizeOverride] is
 * the live size while a pinch is in progress.
 */
@Composable
fun codeTextStyle(languageId: String? = null, fontSizeOverride: Int? = null): TextStyle {
    val settings = LocalSettings.current
    val fontSize = fontSizeOverride ?: settings.get(SettingsSchema.editorFontSize, languageId)
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

/**
 * Inset of the text inside the editable surface. Named because three things must agree on
 * it: the text field, the gutter (so line numbers and glyphs sit on their lines) and
 * [EditorGeometry]'s viewport mapping.
 */
private const val TEXT_PADDING_H_DP = 8
private const val TEXT_PADDING_V_DP = 4



