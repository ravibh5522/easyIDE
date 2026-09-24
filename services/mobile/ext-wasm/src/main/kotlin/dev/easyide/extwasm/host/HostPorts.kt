package dev.easyide.extwasm.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URI

/*
 * Ports: everything a host function touches outside `:ext-wasm`, implemented in `:app`
 * (which bridges them to `:extensions`, `:lsp`, `:sandbox-runtime` and the UI) and by fakes
 * in `easyide-ext test`. Capability checks happen before a port is called, in
 * HostCallRouter; ports only perform the effect. Arguments are the sdk-reference JSON
 * shapes, passed through, so an additive v1 field needs no port change.
 *
 * Ports run on the calling extension's worker thread. A port that needs the main thread
 * hops there itself (`withContext(Dispatchers.Main)`), blocking only that worker. A typed
 * failure is thrown as HostCallException; any other exception becomes E_INTERNAL.
 */

/** Active editor state (`WorkspaceViewModel` in `:app`). Paths are guest paths. */
interface EditorPort {
    /** `{path, languageId, version, selections}` of the active editor, or null if none. */
    suspend fun active(): JsonElement?
    suspend fun getText(range: JsonElement?): String
    suspend fun applyEdits(extensionId: String, edits: JsonArray): JsonElement?
    suspend fun setSelections(extensionId: String, args: JsonObject)
    suspend fun insertSnippet(extensionId: String, args: JsonObject)
    suspend fun decorate(extensionId: String, kind: String, items: JsonArray)
}

/**
 * Project files by normalised guest path. The implementation maps to host paths through the
 * sandbox path mapping and MUST throw HostCallException(E_CAPABILITY) when the canonical host
 * path leaves the allowed root (symlink escape), because only it can see the real target.
 */
interface FilePort {
    suspend fun read(path: String, args: JsonObject): JsonElement
    suspend fun write(extensionId: String, path: String, args: JsonObject)
    suspend fun stat(path: String): JsonElement?
    suspend fun list(path: String): JsonElement
    suspend fun delete(extensionId: String, path: String)
    suspend fun rename(extensionId: String, from: String, to: String)

    /** Starts delivering `fs.changed` for [glob] through `WasmHost.postEvent`. */
    suspend fun watch(extensionId: String, glob: String)

    /** Stops every watch of [extensionId]; called when its instance is dropped. */
    fun unwatchAll(extensionId: String)
}

/** Messages, pickers and contributed UI (`:app`). Items are namespaced by extension id. */
interface UiPort {
    suspend fun showMessage(extensionId: String, args: JsonObject): JsonElement?
    suspend fun showQuickPick(extensionId: String, args: JsonObject): JsonElement?
    suspend fun showInputBox(extensionId: String, args: JsonObject): JsonElement?
    suspend fun setStatusBarItem(extensionId: String, args: JsonObject)
    suspend fun setViewData(extensionId: String, viewId: String, items: JsonElement)
    suspend fun revealStage(extensionId: String, args: JsonObject)
}

/** The Pillar 4 command registry. */
interface CommandPort {
    suspend fun execute(extensionId: String, command: String, args: JsonArray): JsonElement?

    /** Capability the target command itself requires (its owner's grant), or null if none. */
    fun requiredCapability(command: String): String?

    /** Binds a contributed command id to this extension's `ext_handle`. */
    fun register(extensionId: String, command: String)
    fun unregisterAll(extensionId: String)
}

/** Settings store; [target] is the sdk-reference `config.set` target (user, environment, project). */
interface ConfigPort {
    fun get(extensionId: String, key: String): JsonElement?
    suspend fun set(extensionId: String, key: String, value: JsonElement, target: String?)
}

interface LspPort {
    suspend fun request(language: String, method: String, params: JsonElement?): JsonElement?
    suspend fun notify(language: String, method: String, params: JsonElement?)
    suspend fun status(language: String): JsonElement
}

/** Delivery of an async handle's progress back into the owning instance's event queue. */
interface HandleSink {
    fun emit(event: String, data: JsonObject)

    /** Final event; frees the handle slot. Exactly one call per started handle. */
    fun finish(event: String, data: JsonObject)
}

enum class ExecOutput { CAPTURE, TERMINAL }

/** A `sandbox.exec` after host policy: [env] already has every reserved key removed. */
data class ExecRequest(
    val argv: List<String>,
    val cwd: String?,
    val env: Map<String, String>,
    val stdin: String?,
    val output: ExecOutput,
)

/**
 * Process start in the extension's environment as argv (no shell string), clean environment
 * plus [ExecRequest.env]. Streams `sandbox.output` and ends with `sandbox.exit`. The process
 * is unconstrained once started (no per-extension isolation, decision 0002).
 */
interface SandboxPort {
    suspend fun start(extensionId: String, handle: Long, request: ExecRequest, sink: HandleSink)
    suspend fun kill(extensionId: String, handle: Long)
}

/** A `net.fetch` that passed the scheme and declared-host checks. */
data class NetRequest(
    val url: URI,
    val method: String,
    val headers: Map<String, String>,
    val body: String?,
    val maxResponseBytes: Int,
    /** Declared-host check for every redirect hop; undeclared -> do not follow. */
    val hostAllowed: (String) -> Boolean,
)

/**
 * HTTPS client. MUST apply [NetGuard.isPublic] to each resolved address before connecting
 * (initial and redirects), follow a redirect only if https and [NetRequest.hostAllowed],
 * cap the body at [NetRequest.maxResponseBytes], and end with one `net.response` finish.
 */
interface NetPort {
    suspend fun fetch(extensionId: String, handle: Long, request: NetRequest, sink: HandleSink)
}

enum class StorageScope(val wire: String) {
    GLOBAL("global"), ENVIRONMENT("environment"), PROJECT("project");

    companion object {
        fun parse(s: String?): StorageScope? = entries.firstOrNull { it.wire == s }
    }
}

/** Per-extension key-value store; no call can address another extension's namespace. */
interface StoragePort {
    suspend fun get(extensionId: String, scope: StorageScope, key: String): JsonElement?
    suspend fun set(extensionId: String, scope: StorageScope, key: String, value: JsonElement)
    suspend fun delete(extensionId: String, scope: StorageScope, key: String)
    suspend fun keys(extensionId: String, scope: StorageScope): List<String>

    /** Bytes used by [extensionId] across all scopes, each entry counted as [entryBytes]. */
    suspend fun usedBytes(extensionId: String): Long

    companion object {
        /** Quota accounting unit (`extensions.storage.quotaKb`): UTF-8 key bytes plus the value's JSON text. */
        fun entryBytes(key: String, value: JsonElement): Long =
            (key.toByteArray(Charsets.UTF_8).size + value.toString().toByteArray(Charsets.UTF_8).size).toLong()
    }
}

/** Secrets the user entered for this extension (`ext/<id>/<name>`); never git or Claude credentials. */
interface SecretPort {
    suspend fun get(extensionId: String, name: String): String?
}

interface ClipboardPort {
    suspend fun read(): String?
    suspend fun write(text: String)
}

enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }

/** The Extension Log panel ring. Must not block. */
fun interface ExtensionLog {
    fun write(extensionId: String, level: LogLevel, message: String)
}

/** Static facts for `host.info`. */
data class HostInfo(val appVersion: String, val apiVersion: String, val locale: String)

/** Every port a host function can reach, bundled for construction in `AppContainer`. */
class HostPorts(
    val info: HostInfo,
    val log: ExtensionLog,
    val editor: EditorPort,
    val files: FilePort,
    val ui: UiPort,
    val commands: CommandPort,
    val config: ConfigPort,
    val lsp: LspPort,
    val sandbox: SandboxPort,
    val net: NetPort,
    val storage: StoragePort,
    val secrets: SecretPort,
    val clipboard: ClipboardPort,
)
