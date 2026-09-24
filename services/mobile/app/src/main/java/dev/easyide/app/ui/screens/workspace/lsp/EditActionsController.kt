package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.app.R
import dev.easyide.app.data.settings.LspSettingsSchema
import dev.easyide.app.ui.screens.workspace.EditorTab
import dev.easyide.app.ui.screens.workspace.decor.DecorationLayer
import dev.easyide.app.ui.screens.workspace.decor.GutterGlyph
import dev.easyide.app.ui.screens.workspace.decor.GutterMarkerDecoration
import dev.easyide.app.ui.screens.workspace.edit.TypingOptions
import dev.easyide.lsp.client.FromServer
import dev.easyide.lsp.protocol.CodeAction
import dev.easyide.lsp.protocol.CodeActionTriggerKind
import dev.easyide.lsp.protocol.FormattingOptions
import dev.easyide.lsp.protocol.Position
import dev.easyide.lsp.protocol.Range
import dev.easyide.lsp.protocol.TextEdit
import dev.easyide.lsp.session.LspRequestException
import dev.easyide.lsp.text.LineIndex
import dev.easyide.lsp.text.TextEdits
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The quick-fix menu anchored at [anchor] of [text]: preferred fixes first, disabled greyed. */
data class CodeActionMenuUi(val path: String, val text: String, val anchor: Int, val actions: List<FromServer<CodeAction>>)

/**
 * Code actions (LSP-29) and formatting (LSP-30). The lightbulb is a gutter marker on the caret
 * line when any non-disabled action exists there (caret idle, `editor.lightbulb.enabled`); a
 * gutter tap or Ctrl+. opens the menu. Formatting edits are VersionBound strict: an answer for
 * an older version is refused rather than applied to newer text.
 */
class EditActionsController(private val ws: LspWorkspace, private val diagnostics: DiagnosticsPresenter) {

    private val menuState = MutableStateFlow<CodeActionMenuUi?>(null)
    val menu: StateFlow<CodeActionMenuUi?> = menuState.asStateFlow()

    private var lightbulbJob: Job? = null
    private var lightbulbLine: Pair<String, Int>? = null

    fun closeMenu() {
        menuState.value = null
    }

    /** Caret idle: refresh the lightbulb for the caret line. */
    fun onCaret(caret: Caret) {
        lightbulbJob?.cancel()
        if (!ws.setting(LspSettingsSchema.lightbulb, caret.path)) return clearLightbulb(caret.path)
        if (ws.documents.doc(caret.path) == null) return
        lightbulbJob = ws.scope.launch {
            delay(LspUiPolicy.CODE_ACTION_DEBOUNCE_MS)
            ws.documents.ensureCurrent(caret.path, caret.text)
            val lines = LineIndex(caret.text)
            val line = lines.position(caret.offset).line
            val actions = fetch(caret.path, lines, line, line, CodeActionTriggerKind.AUTOMATIC) ?: return@launch
            if (ws.tab(caret.path)?.content != caret.text) return@launch
            val model = ws.decorations.model(caret.path)
            if (actions.none { it.value.disabledReason == null }) return@launch clearLightbulb(caret.path)
            val lineStart = lines.offset(Position(line, 0))
            model.set(DecorationLayer.GutterMarkers, LIGHTBULB_SOURCE, listOf(GutterMarkerDecoration(lineStart, GutterGlyph.LIGHTBULB)), caret.text)
            lightbulbLine = caret.path to line
        }
    }

    /** Gutter tap: the lightbulb's line opens its menu. */
    fun onGutterTap(path: String, line: Int) {
        if (lightbulbLine == (path to line)) openMenu(path, line, CodeActionTriggerKind.AUTOMATIC)
    }

    /** Ctrl+. / long-press "Quick fix": actions for the caret line (or selection). */
    fun openMenuAtCaret() {
        val caret = ws.activeCaret() ?: return
        val lines = LineIndex(caret.text)
        val first = lines.position(minOf(caret.selection.start, caret.selection.end)).line
        openMenu(caret.path, first, CodeActionTriggerKind.INVOKED, lines.position(maxOf(caret.selection.start, caret.selection.end)).line)
    }

    private fun openMenu(path: String, line: Int, kind: CodeActionTriggerKind, lastLine: Int = line) {
        val tab = ws.tab(path) ?: return
        ws.scope.launch {
            ws.documents.ensureCurrent(path, tab.content)
            val lines = LineIndex(tab.content)
            val actions = fetch(path, lines, line, lastLine, kind).orEmpty()
            if (actions.isEmpty()) return@launch ws.host.showStatus(ws.host.string(R.string.lsp_no_code_actions))
            val sorted = actions.sortedWith(compareBy<FromServer<CodeAction>> { it.value.disabledReason != null }.thenBy { !it.value.isPreferred })
            menuState.value = CodeActionMenuUi(path, tab.content, lines.offset(Position(line, 0)), sorted)
        }
    }

    /** Applies an action: resolve if needed, then its edit, then its command. */
    fun run(action: FromServer<CodeAction>) {
        closeMenu()
        if (action.value.disabledReason != null) return
        ws.scope.launch { perform(action) }
    }

    private suspend fun perform(action: FromServer<CodeAction>) {
        val full = ws.client.resolveCodeAction(ws.environmentId, ws.projectId, action)
        full.edit?.let { ws.applyWorkspaceEdit(it, full.title) }
        full.command?.let { ws.client.executeCommand(ws.environmentId, ws.projectId, action.server, it) }
    }

    private suspend fun fetch(path: String, lines: LineIndex, first: Int, last: Int, kind: CodeActionTriggerKind, only: List<String>? = null): List<FromServer<CodeAction>>? {
        val ctx = ws.documents.context(path) ?: return null
        val range = Range(Position(first, 0), lines.position(lines.offset(Position(last + 1, 0))))
        return ws.client.codeActions(ctx, range, diagnostics.onLines(path, first, last), only, kind)
    }

    private fun clearLightbulb(path: String) {
        ws.decorations.model(path).clear(DecorationLayer.GutterMarkers, LIGHTBULB_SOURCE)
        lightbulbLine = null
    }

    // ---- formatting -----------------------------------------------------------------------

    /** Shift+Alt+F. With a selection and a range formatter, only the selection. */
    fun formatDocument(selectionOnly: Boolean = false) {
        val caret = ws.activeCaret() ?: return
        val ctx = ws.documents.context(caret.path) ?: return
        ws.scope.launch {
            ws.documents.ensureCurrent(caret.path, caret.text)
            val formatter = ws.setting(LspSettingsSchema.defaultFormatter, caret.path).ifBlank { null }
            val result = try {
                if (selectionOnly && !caret.selection.collapsed) {
                    val lines = LineIndex(caret.text)
                    val range = lines.range(minOf(caret.selection.start, caret.selection.end), maxOf(caret.selection.start, caret.selection.end))
                    ws.client.rangeFormatting(ctx, range, options(), formatter)
                } else {
                    ws.client.formatting(ctx, options(), formatter)
                }
            } catch (e: LspRequestException) {
                return@launch ws.host.showStatus(ws.host.string(R.string.lsp_format_failed, e.message.orEmpty()))
            } ?: return@launch ws.host.showStatus(ws.host.string(R.string.lsp_no_formatter))
            ws.applyToBuffer(caret.path, result.version, result.value)
        }
    }

    /** After a typed character that the formatting owner listed (`editor.formatOnType`). */
    fun onTyped(caret: Caret, c: Char) {
        if (!ws.setting(LspSettingsSchema.formatOnType, caret.path)) return
        val ctx = ws.documents.context(caret.path) ?: return
        ws.scope.launch {
            ws.documents.ensureCurrent(caret.path, caret.text)
            val formatter = ws.setting(LspSettingsSchema.defaultFormatter, caret.path).ifBlank { null }
            val result = try {
                ws.client.onTypeFormatting(ctx, LineIndex(caret.text).position(caret.offset), c.toString(), options(), formatter)
            } catch (e: LspRequestException) {
                null
            } ?: return@launch
            ws.applyToBuffer(caret.path, result.version, result.value)
        }
    }

    /**
     * Save participants (lsp-client.md 5.3): `editor.codeActionsOnSave` kinds, then formatting
     * (`willSaveWaitUntil` when the owner supports it, else `formatting`). Returns the text to
     * write. Never blocks a save on a failing or slow server: each step is bounded by the
     * request timeout and failures leave the text as it was.
     */
    suspend fun beforeSave(tab: EditorTab): String {
        val path = tab.relativePath
        val ctx = ws.documents.context(path) ?: return tab.content
        var text = tab.content
        val timeout = ws.settings[LspSettingsSchema.requestTimeoutMs].toLong()
        val kinds = (ws.setting(LspSettingsSchema.codeActionsOnSave, path) as? JsonObject).orEmpty()
            .filterValues { (it as? JsonPrimitive)?.booleanOrNull == true }.keys.toList()
        for (kind in kinds) {
            ws.documents.ensureCurrent(path, text)
            val lines = LineIndex(text)
            val actions = withTimeoutOrNull(timeout) { fetch(path, lines, 0, lines.lineCount - 1, CodeActionTriggerKind.INVOKED, listOf(kind)) }
            val action = actions?.firstOrNull { it.value.disabledReason == null } ?: continue
            withTimeoutOrNull(timeout) { perform(action) }
            text = ws.tab(path)?.content ?: text
        }
        if (!ws.setting(LspSettingsSchema.formatOnSave, path)) return text
        ws.documents.ensureCurrent(path, text)
        val formatter = ws.setting(LspSettingsSchema.defaultFormatter, path).ifBlank { null }
        val edits: FromServer<List<TextEdit>>? = withTimeoutOrNull(timeout) {
            ws.client.willSaveWaitUntil(ctx, formatter) ?: try {
                ws.client.formatting(ctx, options(), formatter)
            } catch (e: LspRequestException) {
                null
            }
        }
        if (edits == null || edits.value.isEmpty() || (edits.version != null && edits.version != ws.documents.version(path))) return text
        val formatted = TextEdits.apply(text, edits.value) ?: return text
        if (formatted != text) ws.host.setContent(path, formatted)
        return formatted
    }

    /** `FormattingOptions` from the editor's indentation (the typing rules' single source). */
    private fun options(): FormattingOptions {
        val unit = TypingOptions().indentUnit
        val spaces = unit.all { it == ' ' }
        return FormattingOptions(tabSize = if (spaces) unit.length else DEFAULT_TAB_SIZE, insertSpaces = spaces)
    }

    private companion object {
        const val LIGHTBULB_SOURCE = "lsp:lightbulb"

        /** Tab width reported when the editor indents with tabs (VS Code's `editor.tabSize` default). */
        const val DEFAULT_TAB_SIZE = 4
    }
}
