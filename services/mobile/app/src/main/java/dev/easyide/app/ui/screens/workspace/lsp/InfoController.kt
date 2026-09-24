package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.app.data.settings.LspSettingsSchema
import dev.easyide.lsp.protocol.LspFeature
import dev.easyide.lsp.protocol.MarkupKind
import dev.easyide.lsp.protocol.SignatureHelp
import dev.easyide.lsp.protocol.SignatureHelpContext
import dev.easyide.lsp.text.LineIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a hover card came from: touch long-press and the shortcut get an action row, mouse does not. */
enum class HoverOrigin { LONG_PRESS, MOUSE, KEYBOARD }

/**
 * A hover card anchored to `[start, end)` of [text]. [markdown] is every server's answer
 * joined; [languageId] picks the grammar for code fences.
 */
data class HoverUi(
    val path: String,
    val text: String,
    val start: Int,
    val end: Int,
    val markdown: String,
    val languageId: String,
    val fileName: String,
    val origin: HoverOrigin,
)

/** Signature help anchored at the caret offset [anchor] of [text]. */
data class SignatureUi(val path: String, val text: String, val anchor: Int, val help: SignatureHelp)

/**
 * Hover (LSP-23) and signature help (LSP-22). Hover: long-press, mouse rest for
 * `editor.hover.delay`, or Ctrl+K Ctrl+I; dismissed by any edit, by the caret leaving its range,
 * or by the mouse leaving. Signature help: on the servers' trigger characters, updated on their
 * retrigger characters and caret moves while shown, closed on Escape or an empty answer.
 */
class InfoController(private val ws: LspWorkspace) {

    private val hoverState = MutableStateFlow<HoverUi?>(null)
    private val signatureState = MutableStateFlow<SignatureUi?>(null)
    val hover: StateFlow<HoverUi?> = hoverState.asStateFlow()
    val signature: StateFlow<SignatureUi?> = signatureState.asStateFlow()

    private var hoverJob: Job? = null
    private var signatureJob: Job? = null
    private var mouseAt: Int? = null

    fun dismissHover() {
        hoverJob?.cancel()
        hoverState.value = null
    }

    fun dismissSignature() {
        signatureJob?.cancel()
        signatureState.value = null
    }

    fun showHover(path: String, offset: Int, origin: HoverOrigin) {
        if (!ws.setting(LspSettingsSchema.hoverEnabled, path)) return
        val tab = ws.tab(path) ?: return
        val doc = ws.documents.doc(path) ?: return
        val ctx = ws.documents.context(path) ?: return
        hoverJob?.cancel()
        hoverJob = ws.scope.launch {
            ws.documents.ensureCurrent(path, tab.content)
            val lines = LineIndex(tab.content)
            val answers = ws.client.hover(ctx, lines.position(offset))
            if (answers.isEmpty() || ws.tab(path)?.content != tab.content) return@launch
            val range = answers.firstNotNullOfOrNull { it.value.range }
            val start = range?.let { lines.offset(it.start) } ?: CompletionModel.wordStart(tab.content, offset)
            val end = range?.let { lines.offset(it.end) } ?: wordEnd(tab.content, offset)
            val markdown = answers.joinToString(SEPARATOR) { a ->
                if (a.value.contents.kind == MarkupKind.MARKDOWN) a.value.contents.value else "```\n${a.value.contents.value}\n```"
            }
            hoverState.value = HoverUi(path, tab.content, start, end, markdown, doc.languageId, doc.fileName, origin)
        }
    }

    /** Mouse rest: a hover after the delay, unless the pointer is still inside the shown card's range. */
    fun onPointerHover(path: String, offset: Int?) {
        mouseAt = offset
        val shown = hoverState.value
        if (offset == null) {
            if (shown?.origin == HoverOrigin.MOUSE) dismissHover()
            hoverJob?.cancel()
            return
        }
        if (shown != null && shown.path == path && offset in shown.start..shown.end) return
        if (shown?.origin == HoverOrigin.MOUSE) hoverState.value = null
        hoverJob?.cancel()
        hoverJob = ws.scope.launch {
            delay(ws.setting(LspSettingsSchema.hoverDelay, path).toLong())
            if (mouseAt == offset) showHover(path, offset, HoverOrigin.MOUSE)
        }
    }

    /** Explicit signature help (Ctrl+Shift+Space). */
    fun triggerSignature() {
        val caret = ws.activeCaret() ?: return
        requestSignature(caret, TRIGGER_INVOKED, null, isRetrigger = signatureState.value != null, delayMs = 0)
    }

    /**
     * Every caret or buffer change. [typed] is the single character just typed, if any: a
     * trigger character opens signature help, a retrigger character (or any move while
     * shown) refreshes it.
     */
    fun onCaret(caret: Caret, typed: Char?, textChanged: Boolean) {
        val hovered = hoverState.value
        if (hovered != null && (hovered.path != caret.path || (textChanged && hovered.text != caret.text) ||
                caret.offset !in hovered.start..hovered.end)
        ) {
            if (hovered.origin != HoverOrigin.MOUSE || textChanged) dismissHover()
        }
        if (!ws.setting(LspSettingsSchema.parameterHints, caret.path)) return
        val shown = signatureState.value
        val lang = ws.documents.doc(caret.path)?.languageId ?: return
        val sessions = ws.manager.sessionsFor(ws.environmentId, ws.projectId, lang).filter { it.supports(LspFeature.SIGNATURE_HELP) }
        val char = typed?.toString()
        val triggers = sessions.flatMap { it.capabilities.value?.signatureTriggerCharacters.orEmpty() }
        val retriggers = sessions.flatMap { it.capabilities.value?.signatureRetriggerCharacters.orEmpty() }
        when {
            char != null && char in triggers -> requestSignature(caret, TRIGGER_CHARACTER, char, isRetrigger = shown != null, delayMs = 0)
            shown != null && shown.path == caret.path -> {
                val kind = if (char != null && char in retriggers) TRIGGER_CHARACTER else TRIGGER_CONTENT_CHANGE
                requestSignature(caret, kind, char?.takeIf { it in retriggers }, isRetrigger = true, delayMs = LspUiPolicy.SIGNATURE_HELP_DEBOUNCE_MS)
            }
        }
    }

    private fun requestSignature(caret: Caret, kind: Int, char: String?, isRetrigger: Boolean, delayMs: Long) {
        val ctx = ws.documents.context(caret.path) ?: return
        signatureJob?.cancel()
        signatureJob = ws.scope.launch {
            if (delayMs > 0) delay(delayMs)
            ws.documents.ensureCurrent(caret.path, caret.text)
            val position = LineIndex(caret.text).position(caret.offset)
            val help = ws.client.signatureHelp(ctx, position, SignatureHelpContext(kind, char, isRetrigger, null))?.value
            val now = ws.activeCaret()
            if (now == null || now.path != caret.path || now.text != caret.text) return@launch
            signatureState.value = help?.let { SignatureUi(caret.path, caret.text, caret.offset, it) }
        }
    }

    private fun wordEnd(text: String, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i < text.length && CompletionModel.isWordChar(text[i])) i++
        return i
    }

    private companion object {
        /** `SignatureHelpTriggerKind`: invoked, trigger character, content change. */
        const val TRIGGER_INVOKED = 1
        const val TRIGGER_CHARACTER = 2
        const val TRIGGER_CONTENT_CHANGE = 3
        const val SEPARATOR = "\n\n---\n\n"
    }
}
