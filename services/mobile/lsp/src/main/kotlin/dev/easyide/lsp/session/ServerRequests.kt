package dev.easyide.lsp.session

import dev.easyide.lsp.diagnostics.DiagnosticStore
import dev.easyide.lsp.json.arr
import dev.easyide.lsp.json.int
import dev.easyide.lsp.json.long
import dev.easyide.lsp.json.mapItems
import dev.easyide.lsp.json.obj
import dev.easyide.lsp.json.putOpt
import dev.easyide.lsp.json.str
import dev.easyide.lsp.jsonrpc.RpcHandler
import dev.easyide.lsp.jsonrpc.methodNotFound
import dev.easyide.lsp.protocol.PublishDiagnostics
import dev.easyide.lsp.protocol.WorkspaceEdit
import dev.easyide.lsp.workspace.ApplyResult
import dev.easyide.lsp.workspace.PathMapper
import dev.easyide.lsp.workspace.WorkspaceEditApplier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A `client/registerCapability` registration, kept for the app (watchers, configuration). */
data class Registration(val id: String, val method: String, val options: JsonObject?)

/** One `$/progress` stream the server reported (status bar item). */
data class WorkDoneProgress(val title: String, val message: String?, val percentage: Int?)

/** `workspace/<kind>/refresh`: re-run that pipeline for visible documents. */
enum class RefreshKind(val method: String) {
    SEMANTIC_TOKENS("workspace/semanticTokens/refresh"),
    INLAY_HINTS("workspace/inlayHint/refresh"),
    CODE_LENS("workspace/codeLens/refresh"),
    DIAGNOSTICS("workspace/diagnostic/refresh"),
}

/**
 * Server-to-client traffic of one session (lsp-client.md 2.5). Runs on the session dispatcher;
 * handlers that wait on the user suspend instead of blocking.
 *
 * Registrations and progress go into the session's flows, which the session resets when the
 * process goes away - a new process registers again.
 */
internal class ServerRequests(
    private val key: ServerKey,
    private val workspaceName: String,
    private val mapper: PathMapper,
    private val configuration: ConfigurationProvider,
    private val ui: LspUi,
    private val edits: WorkspaceEditApplier,
    private val diagnostics: DiagnosticStore,
    private val log: (String) -> Unit,
    /** Push diagnostics are ignored for servers that are pulled (VS Code behaviour, no duplicates). */
    private val usesPull: () -> Boolean,
    private val onDiagnosticsRefresh: () -> Unit,
    private val registrations: MutableStateFlow<Map<String, Registration>>,
    private val progress: MutableStateFlow<Map<String, WorkDoneProgress>>,
    private val refreshes: MutableSharedFlow<RefreshKind>,
) : RpcHandler {
    private val unknownLogged = HashSet<String>()

    override suspend fun onRequest(method: String, params: JsonElement?): JsonElement = when (method) {
        "workspace/configuration" -> configurationReply(params)
        "workspace/workspaceFolders" -> JsonArray(listOf(workspaceFolderJson(mapper, workspaceName)))
        "workspace/applyEdit" -> applyEdit(params)
        "window/workDoneProgress/create" -> JsonNull
        "window/showMessageRequest" -> ask(params)
        "client/registerCapability" -> register(params)
        "client/unregisterCapability" -> unregister(params)
        else -> refresh(method) ?: run {
            logOnce("unhandled request $method")
            throw methodNotFound(method)
        }
    }

    override suspend fun onNotification(method: String, params: JsonElement?) {
        when (method) {
            "textDocument/publishDiagnostics" -> publish(params)
            "window/logMessage" -> params.obj?.let { log("[${MessageType.fromWire(it["type"].int)}] ${it["message"].str.orEmpty()}") }
            "\$/logTrace" -> params.obj?.let { log("trace: ${it["message"].str.orEmpty()}") }
            "window/showMessage" -> showMessage(params)
            "\$/progress" -> onProgress(params)
            "telemetry/event" -> Unit
            else -> logOnce("dropped notification $method")
        }
    }

    private suspend fun configurationReply(params: JsonElement?): JsonElement {
        val items = params.obj?.get("items").mapItems { item ->
            val o = item.obj ?: return@mapItems null
            ConfigurationItem(o["scopeUri"].str, o["section"].str)
        }
        val values = configuration.configuration(key, items)
        // The spec requires exactly one value per item; pad rather than let a port bug desync.
        return JsonArray(items.indices.map { values.getOrNull(it) ?: JsonNull })
    }

    private suspend fun applyEdit(params: JsonElement?): JsonElement {
        val o = params.obj
        val edit = WorkspaceEdit.fromJson(o?.get("edit"))
        val result = if (edit == null) {
            ApplyResult(false, "malformed WorkspaceEdit")
        } else {
            edits.apply(edit, o?.get("label").str ?: key.serverId)
        }
        return buildJsonObject {
            put("applied", JsonPrimitive(result.applied))
            putOpt("failureReason", result.failureReason)
            putOpt("failedChange", result.failedChange)
        }
    }

    private suspend fun ask(params: JsonElement?): JsonElement {
        val o = params.obj ?: return JsonNull
        val actions = o["actions"].mapItems { it.obj?.get("title").str }
        val type = MessageType.fromWire(o["type"].int)
        val message = o["message"].str.orEmpty()
        log("[$type] $message")
        val chosen = ui.ask(key, type, message, actions) ?: return JsonNull
        return buildJsonObject { put("title", JsonPrimitive(chosen)) }
    }

    private fun showMessage(params: JsonElement?) {
        val o = params.obj ?: return
        val type = MessageType.fromWire(o["type"].int)
        val message = o["message"].str.orEmpty()
        log("[$type] $message")
        if (type == MessageType.ERROR || type == MessageType.WARNING) ui.showMessage(key, type, message)
    }

    private fun register(params: JsonElement?): JsonElement {
        val added = params.obj?.get("registrations").mapItems { r ->
            val o = r.obj ?: return@mapItems null
            Registration(o["id"].str ?: return@mapItems null, o["method"].str ?: return@mapItems null, o["registerOptions"].obj)
        }
        registrations.update { it + added.associateBy(Registration::id) }
        added.forEach { log("registered ${it.method} (${it.id})") }
        return JsonNull
    }

    private fun unregister(params: JsonElement?): JsonElement {
        val o = params.obj
        // The spec's field is misspelled "unregisterations"; accept the correct spelling too.
        val list = o?.get("unregisterations")?.arr ?: o?.get("unregistrations")?.arr
        val ids = list.mapItems { it.obj?.get("id").str }.toSet()
        registrations.update { it - ids }
        return JsonNull
    }

    private fun refresh(method: String): JsonElement? {
        val kind = RefreshKind.entries.firstOrNull { it.method == method } ?: return null
        if (kind == RefreshKind.DIAGNOSTICS) onDiagnosticsRefresh()
        refreshes.tryEmit(kind)
        return JsonNull
    }

    private fun publish(params: JsonElement?) {
        val p = PublishDiagnostics.fromJson(params) ?: return
        if (usesPull()) return
        // Diagnostics for a file with no host counterpart could never be shown or navigated to.
        if (mapper.toHost(p.uri) == null) return
        diagnostics.put(p.uri, key, p.version, p.diagnostics)
    }

    private fun onProgress(params: JsonElement?) {
        val o = params.obj ?: return
        val token = o["token"].let { it.str ?: it.long?.toString() } ?: return
        val value = o["value"].obj ?: return
        when (value["kind"].str) {
            "begin" -> progress.update { it + (token to WorkDoneProgress(value["title"].str.orEmpty(), value["message"].str, value["percentage"].int)) }
            "report" -> progress.update { m ->
                val old = m[token] ?: return@update m
                m + (token to old.copy(message = value["message"].str ?: old.message, percentage = value["percentage"].int ?: old.percentage))
            }
            "end" -> progress.update { it - token }
        }
    }

    private fun logOnce(line: String) {
        if (unknownLogged.add(line)) log(line)
    }
}
