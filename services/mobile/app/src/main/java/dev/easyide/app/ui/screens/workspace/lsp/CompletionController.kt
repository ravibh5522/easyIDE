package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.app.data.settings.AcceptOnEnter
import dev.easyide.app.data.settings.LspSettingsSchema
import dev.easyide.app.lsp.ExtensionProviders
import dev.easyide.app.lsp.ProviderQuery
import dev.easyide.app.ui.screens.workspace.edit.SCOPE_COMMENT
import dev.easyide.app.ui.screens.workspace.edit.SCOPE_STRING
import dev.easyide.app.ui.screens.workspace.edit.TextState
import dev.easyide.app.ui.screens.workspace.edit.TypingOptions
import dev.easyide.app.ui.screens.workspace.edit.TypingRules
import dev.easyide.app.ui.screens.workspace.syntax.LanguageConfigs
import dev.easyide.lsp.client.FromServer
import dev.easyide.lsp.protocol.CompletionTriggerKind
import dev.easyide.lsp.protocol.LspFeature
import dev.easyide.lsp.session.ServerKey
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The open completion list: [items] already filtered for the word at [wordStart]. */
data class CompletionUi(val path: String, val wordStart: Int, val items: List<CompletionEntry>, val selected: Int) {
    val focused: CompletionEntry? get() = items.getOrNull(selected)
}

/**
 * Completion (LSP-21, lsp-features.md 4.2): auto-triggered on identifier characters after
 * `editor.quickSuggestionsDelay` (honouring its comments/strings scopes), on server trigger
 * characters, or explicitly (Ctrl+Space); refiltered locally while typing inside the word,
 * re-requested only for `isIncomplete` lists; the focused item resolved; accepted by tap,
 * Enter (`editor.acceptSuggestionOnEnter`), Tab or a commit character, as one buffer change.
 */
class CompletionController(
    private val ws: LspWorkspace,
    private val snippets: SnippetController,
    /** WASM `providers.register{kind: completion}` sources, ranked after the servers (wasm-host.md 11.3). */
    private val providers: ExtensionProviders = ExtensionProviders.NONE,
) {

    private val state = MutableStateFlow<CompletionUi?>(null)
    val ui: StateFlow<CompletionUi?> = state.asStateFlow()

    private var origin: CompletionOrigin? = null
    private var raw: List<CompletionEntry> = emptyList()
    private var incomplete = false
    private var requestJob: Job? = null
    private var resolveJob: Job? = null
    private var typed: Char? = null

    val isOpen: Boolean get() = state.value != null

    fun close() {
        requestJob?.cancel()
        resolveJob?.cancel()
        state.value = null
        origin = null
        raw = emptyList()
    }

    /** Ctrl+Space / key-row button: request now at the caret. */
    fun trigger() {
        val caret = ws.activeCaret() ?: return
        request(caret, CompletionTriggerKind.INVOKED, null, delayMs = 0)
    }

    /**
     * Synchronous part of an edit: Enter or a commit character accepts the focused item
     * instead of inserting, returning the replacement state; also remembers the typed
     * character for [onCaret].
     */
    fun transformEdit(path: String, before: TextState, after: TextState): TextState? {
        typed = CompletionModel.typedChar(before, after)
        val open = state.value?.takeIf { it.path == path } ?: return null
        val entry = open.focused ?: return null
        val inserted = CompletionModel.insertedText(before, after) ?: return null
        if (inserted.startsWith('\n')) {
            val word = before.text.substring(open.wordStart.coerceAtMost(before.max), before.max)
            val accept = when (ws.setting(LspSettingsSchema.acceptSuggestionOnEnter, path)) {
                AcceptOnEnter.ON -> true
                AcceptOnEnter.OFF -> false
                AcceptOnEnter.SMART -> CompletionModel.makesTextualChange(entry, word)
            }
            if (!accept) {
                close()
                return null
            }
            typed = null
            return acceptNow(path, before, entry, AcceptMode.INSERT)?.let { TextState(it.text, it.selection.first, it.selection.end) }
        }
        val c = inserted.singleOrNull() ?: return null
        if (c.toString() !in entry.item.commitCharacters) return null
        val accepted = acceptNow(path, before, entry, AcceptMode.INSERT) ?: return null
        val at = accepted.selection.end
        val text = accepted.text.substring(0, at) + c + accepted.text.substring(at)
        snippets.end()
        return TextState(text, at + 1)
    }

    /** After every buffer or caret change of the active editor. */
    fun onCaret(caret: Caret) {
        val char = typed
        typed = null
        val open = origin
        if (open != null && state.value?.path == caret.path) {
            if (char != null && isTrigger(caret.path, char)) {
                request(caret, CompletionTriggerKind.TRIGGER_CHARACTER, char.toString(), delayMs = 0)
                return
            }
            if (!caret.selection.collapsed || !CompletionModel.canRefilter(open, caret.text, caret.offset)) {
                close()
                return
            }
            if (incomplete && char != null) {
                request(caret, CompletionTriggerKind.INCOMPLETE_RETRIGGER, null, delayMs = 0)
            } else {
                ws.scope.launch { refilter(caret, opening = false) }
            }
            return
        }
        if (char == null || !caret.selection.collapsed) return
        when {
            isTrigger(caret.path, char) && ws.setting(LspSettingsSchema.suggestOnTriggerCharacters, caret.path) ->
                request(caret, CompletionTriggerKind.TRIGGER_CHARACTER, char.toString(), delayMs = 0)
            CompletionModel.isWordChar(char) ->
                request(caret, CompletionTriggerKind.INVOKED, null, ws.setting(LspSettingsSchema.quickSuggestionsDelay, caret.path).toLong(), quick = true)
        }
    }

    fun moveSelection(delta: Int) {
        val cur = state.value ?: return
        if (cur.items.isEmpty()) return
        select((cur.selected + delta).coerceIn(0, cur.items.lastIndex))
    }

    fun select(index: Int) {
        val cur = state.value ?: return
        state.value = cur.copy(selected = index)
        resolveFocused()
    }

    /** Tap on a row, or Tab (REPLACE): waits briefly for resolve so auto-imports come along. */
    fun acceptAsync(index: Int, mode: AcceptMode) {
        val cur = state.value ?: return
        val entry = cur.items.getOrNull(index) ?: return
        ws.scope.launch {
            val full = if (entry.resolved) entry else withTimeoutOrNull(LspUiPolicy.ACCEPT_RESOLVE_TIMEOUT_MS) { resolve(entry) } ?: entry
            val caret = ws.activeCaret()?.takeIf { it.path == cur.path } ?: return@launch
            val before = TextState(caret.text, caret.selection.start, caret.selection.end)
            val accepted = acceptNow(cur.path, before, full, mode) ?: return@launch
            ws.host.setContent(cur.path, accepted.text)
            ws.select(cur.path, accepted.text, accepted.selection.first, accepted.selection.end, reveal = false)
        }
    }

    private fun acceptNow(path: String, before: TextState, entry: CompletionEntry, mode: AcceptMode): Acceptance? {
        val o = origin ?: return null
        val doc = ws.documents.doc(path) ?: return null
        if (!CompletionModel.canRefilter(o, before.text, before.max)) return null
        val lineStart = before.text.lastIndexOf('\n', before.max - 1).let { if (it < 0) 0 else it + 1 }
        val indent = before.text.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
        val word = before.text.substring(CompletionModel.wordStart(before.text, before.max), before.max)
        val vars = mapOf(
            "TM_FILENAME" to doc.fileName,
            "TM_FILENAME_BASE" to doc.fileName.substringBeforeLast('.'),
            "TM_DIRECTORY" to doc.path.substringBeforeLast('/', ""),
            "TM_FILEPATH" to doc.path,
            "TM_CURRENT_WORD" to word,
            "TM_SELECTED_TEXT" to "",
        )
        val accepted = CompletionModel.accept(entry, o, before.text, before.max, mode, indent, TypingOptions().indentUnit, vars::get) ?: return null
        close()
        snippets.start(path, accepted.text, accepted.snippet)
        entry.item.command?.let { cmd ->
            ws.scope.launch { ws.client.executeCommand(ws.environmentId, ws.projectId, entry.server, cmd) }
        }
        return accepted
    }

    private fun request(caret: Caret, kind: CompletionTriggerKind, triggerChar: String?, delayMs: Long, quick: Boolean = false) {
        val ctx = ws.documents.context(caret.path) ?: return
        requestJob?.cancel()
        requestJob = ws.scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (quick && !quickAllowed(caret)) return@launch
            // The tab collector may not have pushed this keystroke yet; the request must see it.
            ws.documents.ensureCurrent(caret.path, caret.text)
            val o = CompletionOrigin(caret.text, caret.offset, CompletionModel.wordStart(caret.text, caret.offset))
            val position = o.lines.position(o.caret)
            val (lists, provided) = coroutineScope {
                val query = ProviderQuery(ctx.uri, ctx.languageId, ws.documents.version(caret.path), position, triggerChar)
                val fromExtensions = async { providers.completion(query) }
                ws.client.completion(ctx, position, kind, triggerChar) to fromExtensions.await()
            }
            val latest = ws.activeCaret()?.takeIf { it.path == caret.path } ?: return@launch
            if (!CompletionModel.canRefilter(o, latest.text, latest.offset)) return@launch
            origin = o
            // Provider items are final (no resolve round trip) and never run a server command.
            raw = lists.flatMap { r -> r.value.items.map { CompletionEntry(r.server, it) } } + provided.flatMap { p ->
                val source = ServerKey(ws.environmentId, ws.projectId, EXTENSION_SOURCE_PREFIX + p.source)
                p.items.map { CompletionEntry(source, it.copy(command = null), resolved = true) }
            }
            incomplete = lists.any { it.value.isIncomplete }
            refilter(latest, opening = true)
        }
    }

    private suspend fun refilter(caret: Caret, opening: Boolean) {
        val o = origin ?: return
        val word = caret.text.substring(o.wordStart, caret.offset)
        val all = raw
        val items = withContext(Dispatchers.Default) { CompletionModel.filter(all, word, LspUiPolicy.MAX_COMPLETION_ITEMS) }
        if (items.isEmpty()) {
            state.value = null
            return
        }
        val keep = state.value?.focused?.takeUnless { opening }?.let { f -> items.indexOfFirst { it.item === f.item } }?.takeIf { it >= 0 }
        state.value = CompletionUi(caret.path, o.wordStart, items, keep ?: CompletionModel.initialSelection(items))
        resolveFocused()
    }

    private fun resolveFocused() {
        val entry = state.value?.focused ?: return
        if (entry.resolved) return
        resolveJob?.cancel()
        resolveJob = ws.scope.launch {
            delay(LspUiPolicy.COMPLETION_RESOLVE_DEBOUNCE_MS)
            resolve(entry)
        }
    }

    /** Resolves [entry] on its server and swaps the result into the list; returns it. */
    private suspend fun resolve(entry: CompletionEntry): CompletionEntry {
        val item = ws.client.resolveCompletion(ws.environmentId, ws.projectId, FromServer(entry.server, entry.item, null))
        val full = CompletionEntry(entry.server, item, resolved = true)
        raw = raw.map { if (it.item === entry.item) full else it }
        state.value?.let { cur -> state.value = cur.copy(items = cur.items.map { if (it.item === entry.item) full else it }) }
        return full
    }

    private fun isTrigger(path: String, c: Char): Boolean {
        val lang = ws.documents.doc(path)?.languageId ?: return false
        return ws.manager.sessionsFor(ws.environmentId, ws.projectId, lang).any { s ->
            s.supports(LspFeature.COMPLETION) && s.capabilities.value?.completionTriggerCharacters?.contains(c.toString()) == true
        }
    }

    /** `editor.quickSuggestions` for the scope at the caret (comment / string / other). */
    private suspend fun quickAllowed(caret: Caret): Boolean {
        val doc = ws.documents.doc(caret.path) ?: return false
        val setting = ws.setting(LspSettingsSchema.quickSuggestions, caret.path)
        if (setting is JsonPrimitive) return setting.booleanOrNull == true
        val obj = setting as? JsonObject ?: return false
        val scope = withContext(Dispatchers.Default) {
            val lineStart = caret.text.lastIndexOf('\n', caret.offset - 1).let { if (it < 0) 0 else it + 1 }
            TypingRules.lineScope(caret.text.substring(lineStart, caret.offset), LanguageConfigs.forFile(doc.fileName))
        }
        val key = when (scope) {
            SCOPE_COMMENT -> "comments"
            SCOPE_STRING -> "strings"
            else -> "other"
        }
        return (obj[key] as? JsonPrimitive)?.booleanOrNull ?: (key == "other")
    }

    private companion object {
        /** Server id of provider entries, so they never collide with a real server's key. */
        const val EXTENSION_SOURCE_PREFIX = "extension:"
    }
}
