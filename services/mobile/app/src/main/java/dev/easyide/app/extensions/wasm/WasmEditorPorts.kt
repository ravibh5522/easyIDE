package dev.easyide.app.extensions.wasm

import dev.easyide.app.extensions.host.ActiveDocument
import dev.easyide.app.extensions.host.TextEdits
import dev.easyide.app.extensions.host.WorkspaceBridge
import dev.easyide.extensions.action.LspOutcome
import dev.easyide.extensions.action.LspThen
import dev.easyide.extensions.action.ResolvedTextEdit
import dev.easyide.extensions.action.TextPosition
import dev.easyide.extensions.action.TextRange
import dev.easyide.extensions.whenclause.ContextKeys
import dev.easyide.extwasm.ErrorCode
import dev.easyide.extwasm.HostCallException
import dev.easyide.extwasm.host.EditorPort
import dev.easyide.extwasm.host.GuestPaths
import dev.easyide.extwasm.host.LspPort
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * `editor.*` against the open workspace's active editor, through the same [WorkspaceBridge]
 * the L1 actions use. With no workspace or no active editor the call fails E_UNAVAILABLE
 * (never a guessed project). Positions are LSP-style, 0-based line/character; paths are
 * guest paths. Capabilities were checked by the host router.
 */
class WasmEditorPort(private val workspace: () -> WorkspaceBridge?) : EditorPort {

    override suspend fun active(): JsonElement? {
        val doc = workspace()?.activeDocument() ?: return null
        return buildJsonObject {
            put(PATH, doc.path)
            put(LANGUAGE_ID, doc.languageId)
            put(VERSION, doc.version)
            put(SELECTIONS, JsonArray(listOf(range(doc.text, doc.selectionStart, doc.selectionEnd))))
        }
    }

    override suspend fun getText(range: JsonElement?): String {
        val doc = document()
        if (range == null || range == JsonNull) return doc.text
        val (from, to) = offsets(doc.text, range)
        return doc.text.substring(from, to)
    }

    override suspend fun applyEdits(extensionId: String, edits: JsonArray): JsonElement {
        val bridge = bridge()
        val doc = bridge.activeDocument()
        val resolved = edits.map { e ->
            val o = e as? JsonObject ?: args("every edit must be an object")
            val path = (o[PATH] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { p -> GuestPaths.normalize(p) ?: args("\"path\" must be an absolute guest path") }
                ?: doc?.path ?: unavailable()
            if (!GuestPaths.inProject(path)) throw HostCallException(ErrorCode.E_CAPABILITY, "editor edits are limited to /workspace")
            val text = (o[NEW_TEXT] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: args("\"newText\" must be a string")
            ResolvedTextEdit(path, textRange(o[RANGE] ?: args("\"range\" is required")), text)
        }
        return JsonPrimitive(bridge.applyEdits(resolved))
    }

    override suspend fun setSelections(extensionId: String, args: JsonObject) {
        val bridge = bridge()
        val doc = bridge.activeDocument() ?: unavailable()
        val first = (args[SELECTIONS] as? JsonArray)?.firstOrNull() ?: args("\"selections\" must be a non-empty array")
        val (from, to) = offsets(doc.text, first)
        if (!bridge.select(doc.path, from, to)) unavailable()
    }

    override suspend fun insertSnippet(extensionId: String, args: JsonObject) {
        fun str(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val body = str(SNIPPET) ?: str(BODY)
        val name = str(NAME)
        if (body == null && name == null) args("\"snippet\" or \"name\" is required")
        if (!bridge().insertSnippet(body, name, str(LANGUAGE))) unavailable()
    }

    override suspend fun decorate(extensionId: String, kind: String, items: JsonArray) {
        throw HostCallException(ErrorCode.E_UNAVAILABLE, "editor decorations from extensions are not shown yet")
    }

    private fun bridge(): WorkspaceBridge = workspace() ?: throw HostCallException(ErrorCode.E_UNAVAILABLE, "no workspace is open")

    private fun document(): ActiveDocument = bridge().activeDocument() ?: unavailable()

    private fun unavailable(): Nothing = throw HostCallException(ErrorCode.E_UNAVAILABLE, "no active editor")

    private fun args(message: String): Nothing = throw HostCallException(ErrorCode.E_ARGS, message)

    private fun position(e: JsonElement?): TextPosition {
        val o = e as? JsonObject ?: args("a position must be {line, character}")
        val line = (o[LINE] as? JsonPrimitive)?.intOrNull ?: args("\"line\" must be an integer")
        val character = (o[CHARACTER] as? JsonPrimitive)?.intOrNull ?: args("\"character\" must be an integer")
        return TextPosition(line, character)
    }

    private fun textRange(e: JsonElement): TextRange {
        val o = e as? JsonObject ?: args("a range must be {start, end}")
        return TextRange(position(o[START]), position(o[END]))
    }

    private fun offsets(text: String, range: JsonElement): Pair<Int, Int> {
        val r = textRange(range)
        val starts = TextEdits.lineStarts(text)
        val from = TextEdits.offsetOf(text, starts, r.start) ?: args("range start is outside the document")
        val to = TextEdits.offsetOf(text, starts, r.end) ?: args("range end is outside the document")
        if (to < from) args("range end is before its start")
        return from to to
    }

    private fun range(text: String, from: Int, to: Int) = buildJsonObject {
        put(START, positionJson(text, from.coerceIn(0, text.length)))
        put(END, positionJson(text, to.coerceIn(0, text.length)))
    }

    private fun positionJson(text: String, offset: Int) = buildJsonObject {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        put(LINE, text.substring(0, offset).count { it == '\n' })
        put(CHARACTER, offset - lineStart)
    }

    private companion object {
        const val PATH = "path"
        const val LANGUAGE_ID = "languageId"
        const val LANGUAGE = "language"
        const val VERSION = "version"
        const val SELECTIONS = "selections"
        const val RANGE = "range"
        const val START = "start"
        const val END = "end"
        const val LINE = "line"
        const val CHARACTER = "character"
        const val NEW_TEXT = "newText"
        const val SNIPPET = "snippet"
        const val BODY = "body"
        const val NAME = "name"
    }
}

/**
 * `lsp.*` (capability `lsp.request`, checked by the host) through the workspace's
 * [dev.easyide.app.ui.screens.workspace.lsp.LspRequestGateway], the same route as the L1
 * `lspRequest` action with `then: none`. `lsp.notify` has no client path yet (E_UNAVAILABLE).
 */
class WasmLspPort(
    private val workspace: () -> WorkspaceBridge?,
    /** Context-key lookup: `lspState:<lang>` / `lspReady:<lang>` feed `lsp.status`. */
    private val contextKey: (String) -> JsonElement?,
) : LspPort {

    override suspend fun request(language: String, method: String, params: JsonElement?): JsonElement? {
        val bridge = workspace() ?: throw HostCallException(ErrorCode.E_UNAVAILABLE, "no workspace is open for a $language server")
        return when (val r = bridge.lspRequest(language, method, params?.takeIf { it != JsonNull }, LspThen.NONE)) {
            is LspOutcome.Result -> r.value
            is LspOutcome.Unavailable -> throw HostCallException(ErrorCode.E_UNAVAILABLE, r.reason)
            is LspOutcome.Failed -> throw HostCallException(ErrorCode.E_INTERNAL, r.message)
        }
    }

    override suspend fun notify(language: String, method: String, params: JsonElement?) {
        throw HostCallException(ErrorCode.E_UNAVAILABLE, "lsp.notify is not available to extensions yet")
    }

    override suspend fun status(language: String): JsonElement = buildJsonObject {
        put(STATE, contextKey(ContextKeys.lspState(language).name) ?: JsonPrimitive(NONE))
        put(READY, contextKey(ContextKeys.lspReady(language).name) ?: JsonPrimitive(false))
    }

    private companion object {
        const val STATE = "state"
        const val READY = "ready"
        const val NONE = "none"
    }
}
