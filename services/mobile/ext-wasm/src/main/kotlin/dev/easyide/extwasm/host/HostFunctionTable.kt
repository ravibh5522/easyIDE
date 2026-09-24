package dev.easyide.extwasm.host

import dev.easyide.extwasm.ErrorCode
import dev.easyide.extwasm.HostCallException
import dev.easyide.extwasm.WasmPolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One row of the dispatch table: the `fn` name, the capability rule checked before the
 * handler runs, and whether the call mutates user state (logged with the extension id,
 * threat-model B2 "R").
 */
internal class HostFn(
    val name: String,
    val rule: Rule,
    val mutating: Boolean = false,
    val handler: suspend HostCallScope.(JsonObject) -> JsonElement?,
)

/** Event names `events.subscribe` accepts (sdk-reference events row). */
internal val SUBSCRIBABLE_EVENTS = setOf(
    "workspace.didOpen", "workspace.didChange", "workspace.didSave", "workspace.didClose",
    "editor.didChangeSelection", "config.didChange", "lsp.didChangeState",
)

/**
 * The static, declarative host-function table: the single L2 enforcement point
 * (wasm-host.md sec 9). Adding a v1 function is one row here; `host.functions` lists them.
 */
internal object HostFunctionTable {

    val rows: Map<String, HostFn> by lazy { build().associateBy { it.name } }

    @Suppress("LongMethod")
    private fun build(): List<HostFn> = listOf(
        HostFn("host.functions", Rule.None) { JsonArray(rows.keys.sorted().map(::JsonPrimitive)) },
        HostFn("host.info", Rule.None) {
            buildJsonObject {
                put("appVersion", ports.info.appVersion); put("apiVersion", ports.info.apiVersion)
                put("locale", ports.info.locale); put("abi", WasmPolicy.ABI_VERSION)
            }
        },
        HostFn("log.write", Rule.None) { a ->
            val level = LogLevel.entries.firstOrNull { it.name.equals(a.str("level"), ignoreCase = true) }
                ?: badArgs("\"level\" must be one of trace, debug, info, warn, error")
            ports.log.write(ext.id, level, a.str("message")); null
        },

        HostFn("editor.active", Rule.Needs(Cap.FS_READ)) { ports.editor.active() },
        HostFn("editor.getText", Rule.Needs(Cap.FS_READ)) { a -> JsonPrimitive(ports.editor.getText(a["range"])) },
        HostFn("editor.applyEdits", Rule.Needs(Cap.FS_WRITE), mutating = true) { a ->
            ports.editor.applyEdits(ext.id, a.arr("edits"))
        },
        HostFn("editor.setSelections", Rule.Needs(Cap.FS_WRITE)) { a -> ports.editor.setSelections(ext.id, a); null },
        HostFn("editor.insertSnippet", Rule.None, mutating = true) { a -> ports.editor.insertSnippet(ext.id, a); null },
        HostFn("editor.decorate", Rule.Needs(Cap.FS_WRITE)) { a ->
            val kind = a.str("kind")
            if (kind !in DECORATION_KINDS) badArgs("\"kind\" must be one of $DECORATION_KINDS")
            ports.editor.decorate(ext.id, kind, a.arr("items")); null
        },

        HostFn("fs.read", Rule.Paths(write = false, listOf("path"))) { a -> ports.files.read(path(a, "path"), a) },
        HostFn("fs.stat", Rule.Paths(write = false, listOf("path"))) { a -> ports.files.stat(path(a, "path")) },
        HostFn("fs.list", Rule.Paths(write = false, listOf("path"))) { a -> ports.files.list(path(a, "path")) },
        HostFn("fs.watch", Rule.WatchGlob) { a -> ports.files.watch(ext.id, a.str("glob")); null },
        HostFn("fs.write", Rule.Paths(write = true, listOf("path")), mutating = true) { a ->
            ports.files.write(ext.id, path(a, "path"), a); null
        },
        HostFn("fs.delete", Rule.Paths(write = true, listOf("path")), mutating = true) { a ->
            ports.files.delete(ext.id, path(a, "path")); null
        },
        HostFn("fs.rename", Rule.Paths(write = true, listOf("from", "to")), mutating = true) { a ->
            ports.files.rename(ext.id, path(a, "from"), path(a, "to")); null
        },

        HostFn("events.subscribe", Rule.None) { a ->
            val names = a.arr("names").strings("\"names\"")
            names.firstOrNull { it !in SUBSCRIBABLE_EVENTS }?.let { badArgs("unknown event $it") }
            session.subscriptions += names; null
        },

        HostFn("config.get", Rule.SettingKey(write = false)) { a -> ports.config.get(ext.id, a.str("key")) },
        HostFn("config.set", Rule.SettingKey(write = true), mutating = true) { a ->
            ports.config.set(ext.id, a.str("key"), a["value"] ?: JsonNull, a.optStr("target")); null
        },

        HostFn("ui.showMessage", Rule.None) { a -> ports.ui.showMessage(ext.id, a) },
        HostFn("ui.showQuickPick", Rule.None) { a -> ports.ui.showQuickPick(ext.id, a) },
        HostFn("ui.showInputBox", Rule.None) { a -> ports.ui.showInputBox(ext.id, a) },
        HostFn("ui.setStatusBarItem", Rule.None) { a -> a.str("id"); ports.ui.setStatusBarItem(ext.id, a); null },
        HostFn("ui.setViewData", Rule.ViewTarget) { a ->
            ports.ui.setViewData(ext.id, a.str("viewId"), a["items"] ?: JsonArray(emptyList())); null
        },
        HostFn("ui.revealStage", Rule.None) { a -> ports.ui.revealStage(ext.id, a); null },

        HostFn("commands.execute", Rule.CommandTarget) { a ->
            val command = a.str("command")
            // The instance is blocked in this call; running its own command would wait on itself.
            if (command in ext.commands) {
                throw HostCallException(ErrorCode.E_UNAVAILABLE, "$command belongs to this busy extension")
            }
            ports.commands.execute(ext.id, command, a.arr("args"))
        },
        HostFn("commands.register", Rule.DeclaredCommand) { a -> ports.commands.register(ext.id, a.str("command")); null },
        HostFn("providers.register", Rule.DeclaredProvider) { a ->
            val languages = a.arr("languages").strings("\"languages\"")
            session.registerProvider(ProviderRegistration(ext.id, a.str("kind"), languages)); null
        },

        HostFn("lsp.request", Rule.Needs(Cap.LSP_REQUEST)) { a ->
            ports.lsp.request(a.str("language"), a.str("method"), a["params"])
        },
        HostFn("lsp.notify", Rule.Needs(Cap.LSP_REQUEST)) { a ->
            ports.lsp.notify(a.str("language"), a.str("method"), a["params"]); null
        },
        HostFn("lsp.status", Rule.Needs(Cap.LSP_REQUEST)) { a -> ports.lsp.status(a.str("language")) },

        HostFn("sandbox.exec", Rule.Needs(Cap.SANDBOX_EXEC), mutating = true) { a -> sandboxExec(a) },
        HostFn("sandbox.kill", Rule.Needs(Cap.SANDBOX_EXEC)) { a -> ports.sandbox.kill(ext.id, a.long("handle")); null },

        HostFn("clipboard.read", Rule.Needs(Cap.CLIPBOARD)) { ports.clipboard.read()?.let(::JsonPrimitive) },
        HostFn("clipboard.write", Rule.Needs(Cap.CLIPBOARD), mutating = true) { a -> ports.clipboard.write(a.str("text")); null },

        HostFn("net.fetch", Rule.NetworkUrl, mutating = true) { a -> netFetch(a) },

        HostFn("storage.get", Rule.None) { a -> ports.storage.get(ext.id, scope(a), a.str("key")) },
        HostFn("storage.set", Rule.None) { a -> storageSet(a); null },
        HostFn("storage.delete", Rule.None) { a -> ports.storage.delete(ext.id, scope(a), a.str("key")); null },
        HostFn("storage.keys", Rule.None) { a -> JsonArray(ports.storage.keys(ext.id, scope(a)).map(::JsonPrimitive)) },

        HostFn("secrets.get", Rule.Needs(Cap.SECRETS_READ)) { a ->
            ports.secrets.get(ext.id, a.str("name"))?.let(::JsonPrimitive)
        },
    )

    private val DECORATION_KINDS = setOf("diagnostic", "inlay", "gutter")

    private fun path(a: JsonObject, key: String): String =
        GuestPaths.normalize(a.str(key)) ?: badArgs("\"$key\" must be an absolute guest path")

    private fun scope(a: JsonObject): StorageScope =
        StorageScope.parse(a.optStr("scope") ?: StorageScope.GLOBAL.wire)
            ?: badArgs("\"scope\" must be one of global, environment, project")

    private suspend fun HostCallScope.sandboxExec(a: JsonObject): JsonElement {
        val argv = a.arr("argv").strings("\"argv\"")
        if (argv.isEmpty()) badArgs("\"argv\" must not be empty")
        val env = a.obj("env").mapValues { (k, v) ->
            (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: badArgs("env value of $k must be a string")
        }.filterKeys { it !in WasmPolicy.RESERVED_ENV_KEYS }
        val output = when (a.optStr("output") ?: "capture") {
            "capture" -> ExecOutput.CAPTURE
            "terminal" -> ExecOutput.TERMINAL
            else -> badArgs("\"output\" must be capture or terminal")
        }
        val request = ExecRequest(argv, a.optStr("cwd"), env, a.optStr("stdin"), output)
        return startHandle { h -> ports.sandbox.start(ext.id, h.id, request, h) }
    }

    private suspend fun HostCallScope.netFetch(a: JsonObject): JsonElement {
        val headers = a.obj("headers").mapValues { (k, v) ->
            (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: badArgs("header $k must be a string")
        }
        val matcher = ext.capabilities.network
        val request = NetRequest(
            url = parseUrl(a.str("url")),
            method = (a.optStr("method") ?: "GET").uppercase(),
            headers = headers,
            body = a.optStr("body"),
            maxResponseBytes = limits.netMaxResponseBytes,
            hostAllowed = matcher::matches,
        )
        return startHandle { h -> ports.net.fetch(ext.id, h.id, request, h) }
    }

    /** Opens a handle, starts the operation, and frees the slot again if starting throws. */
    private suspend fun HostCallScope.startHandle(start: suspend (InstanceSession.OpenHandle) -> Unit): JsonElement {
        val h = session.openHandle()
        try {
            start(h)
        } catch (e: Exception) {
            h.abandon()
            throw e
        }
        return buildJsonObject { put("handle", h.id) }
    }

    private suspend fun HostCallScope.storageSet(a: JsonObject) {
        val scope = scope(a)
        val key = a.str("key")
        val value = a["value"] ?: JsonNull
        val old = ports.storage.get(ext.id, scope, key)?.let { StoragePort.entryBytes(key, it) } ?: 0L
        val after = ports.storage.usedBytes(ext.id) - old + StoragePort.entryBytes(key, value)
        if (after > limits.storageQuotaBytes) {
            throw HostCallException(ErrorCode.E_LIMIT, "storage quota ${limits.storageQuotaBytes} bytes exceeded (extensions.storage.quotaKb)")
        }
        ports.storage.set(ext.id, scope, key, value)
    }
}
