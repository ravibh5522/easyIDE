package dev.easyide.extwasm.testing

import dev.easyide.extwasm.SettingsLookup
import dev.easyide.extwasm.host.CapabilitySet
import dev.easyide.extwasm.host.ClipboardPort
import dev.easyide.extwasm.host.CommandPort
import dev.easyide.extwasm.host.ConfigPort
import dev.easyide.extwasm.host.EditorPort
import dev.easyide.extwasm.host.ExecRequest
import dev.easyide.extwasm.host.ExtensionLog
import dev.easyide.extwasm.host.FilePort
import dev.easyide.extwasm.host.HandleSink
import dev.easyide.extwasm.host.HostInfo
import dev.easyide.extwasm.host.HostPorts
import dev.easyide.extwasm.host.LogLevel
import dev.easyide.extwasm.host.LspPort
import dev.easyide.extwasm.host.NetPort
import dev.easyide.extwasm.host.NetRequest
import dev.easyide.extwasm.host.SandboxPort
import dev.easyide.extwasm.host.SecretPort
import dev.easyide.extwasm.host.StoragePort
import dev.easyide.extwasm.host.StorageScope
import dev.easyide.extwasm.host.UiPort
import dev.easyide.extwasm.host.WasmExtension
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/*
 * Test harness shared by the JVM unit tests and the ART instrumented tests
 * (`:ext-wasm-device-test`): fixture access, settings, and recording fake ports.
 */

/** The compiled `.wat` fixtures, read through [read] (classpath on the JVM, assets on ART). */
open class WasmFixtures(private val read: (name: String) -> ByteArray) {
    fun bytes(name: String): ByteArray = read("$name.wasm")

    fun file(dir: File, name: String): File = File(dir, "$name.wasm").apply { writeBytes(bytes(name)) }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun extension(
        dir: File,
        name: String = "proxy",
        id: String = "acme.test",
        caps: List<String> = emptyList(),
        version: String = "1.0.0",
    ): WasmExtension {
        val f = file(dir, name)
        return WasmExtension(
            id = id, version = version, moduleFile = f, moduleSha256 = sha256(f.readBytes()),
            manifestMemoryMb = null, capabilities = CapabilitySet(caps),
            commands = setOf("test.proxy", "test.spin", "test.trap"),
            providerKinds = setOf("completion"),
            views = setOf("acme.view"), stages = setOf("acme.stage"),
            settingKeys = setOf("acme.enabled"),
        )
    }
}

/** Settings from a plain map; unset keys fall back to defaults. */
class MapSettings(private val values: Map<String, Any> = emptyMap()) : SettingsLookup {
    override fun value(key: String): JsonElement? = when (val v = values[key]) {
        null -> null
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }
}

/** In-memory ports that record every effect so tests can assert on them. */
class FakePorts {
    val logs = CopyOnWriteArrayList<Triple<String, LogLevel, String>>()
    val storage = ConcurrentHashMap<String, JsonElement>()
    val registeredCommands = CopyOnWriteArrayList<Pair<String, String>>()
    val executed = CopyOnWriteArrayList<Pair<String, String>>()
    val execs = CopyOnWriteArrayList<ExecRequest>()
    val fetches = CopyOnWriteArrayList<NetRequest>()
    val sinks = CopyOnWriteArrayList<HandleSink>()
    val writes = CopyOnWriteArrayList<String>()
    val watches = CopyOnWriteArrayList<String>()
    val configSets = CopyOnWriteArrayList<String>()
    val messages = CopyOnWriteArrayList<JsonObject>()
    var editorText = "one two three"
    var clipboard: String? = "clip"
    var commandCapability: Map<String, String> = emptyMap()
    var quickPickGate: CompletableDeferred<Unit>? = null
    var failRead: Exception? = null
    /** Runs inside commands.execute, on the calling extension's worker thread. */
    var onExecute: (suspend (String) -> JsonElement?)? = null

    val ports = HostPorts(
        info = HostInfo("0.1.0-test", "0.1.0", "en-US"),
        log = ExtensionLog { id, level, msg -> logs += Triple(id, level, msg) },
        editor = object : EditorPort {
            override suspend fun active(): JsonElement = buildJsonObject { put("path", "/workspace/a.py"); put("languageId", "python") }
            override suspend fun getText(range: JsonElement?): String = editorText
            override suspend fun applyEdits(extensionId: String, edits: JsonArray): JsonElement = JsonPrimitive(true)
            override suspend fun setSelections(extensionId: String, args: JsonObject) = Unit
            override suspend fun insertSnippet(extensionId: String, args: JsonObject) = Unit
            override suspend fun decorate(extensionId: String, kind: String, items: JsonArray) = Unit
        },
        files = object : FilePort {
            override suspend fun read(path: String, args: JsonObject): JsonElement {
                failRead?.let { throw it }
                return JsonPrimitive("content of $path")
            }
            override suspend fun write(extensionId: String, path: String, args: JsonObject) { writes += path }
            override suspend fun stat(path: String): JsonElement = buildJsonObject { put("size", 1) }
            override suspend fun list(path: String): JsonElement = JsonArray(emptyList())
            override suspend fun delete(extensionId: String, path: String) { writes += "rm $path" }
            override suspend fun rename(extensionId: String, from: String, to: String) { writes += "mv $from $to" }
            override suspend fun watch(extensionId: String, glob: String) { watches += glob }
            override fun unwatchAll(extensionId: String) { watches += "unwatch $extensionId" }
        },
        ui = object : UiPort {
            override suspend fun showMessage(extensionId: String, args: JsonObject): JsonElement? { messages += args; return null }
            override suspend fun showQuickPick(extensionId: String, args: JsonObject): JsonElement {
                quickPickGate?.await()
                return JsonPrimitive("picked")
            }
            override suspend fun showInputBox(extensionId: String, args: JsonObject): JsonElement = JsonPrimitive("typed")
            override suspend fun setStatusBarItem(extensionId: String, args: JsonObject) = Unit
            override suspend fun setViewData(extensionId: String, viewId: String, items: JsonElement) = Unit
            override suspend fun revealStage(extensionId: String, args: JsonObject) = Unit
        },
        commands = object : CommandPort {
            override suspend fun execute(extensionId: String, command: String, args: JsonArray): JsonElement? {
                executed += extensionId to command
                return onExecute?.invoke(command) ?: JsonPrimitive("ran $command")
            }
            override fun requiredCapability(command: String): String? = commandCapability[command]
            override fun register(extensionId: String, command: String) { registeredCommands += extensionId to command }
            override fun unregisterAll(extensionId: String) { registeredCommands.removeIf { it.first == extensionId } }
        },
        config = object : ConfigPort {
            override fun get(extensionId: String, key: String): JsonElement = JsonPrimitive("value of $key")
            override suspend fun set(extensionId: String, key: String, value: JsonElement, target: String?) { configSets += key }
        },
        lsp = object : LspPort {
            override suspend fun request(language: String, method: String, params: JsonElement?): JsonElement = JsonPrimitive(method)
            override suspend fun notify(language: String, method: String, params: JsonElement?) = Unit
            override suspend fun status(language: String): JsonElement = JsonPrimitive("running")
        },
        sandbox = object : SandboxPort {
            override suspend fun start(extensionId: String, handle: Long, request: ExecRequest, sink: HandleSink) {
                execs += request; sinks += sink
            }
            override suspend fun kill(extensionId: String, handle: Long) = Unit
        },
        net = object : NetPort {
            override suspend fun fetch(extensionId: String, handle: Long, request: NetRequest, sink: HandleSink) {
                fetches += request; sinks += sink
            }
        },
        storage = object : StoragePort {
            private fun k(id: String, s: StorageScope, key: String) = "$id/${s.wire}/$key"
            override suspend fun get(extensionId: String, scope: StorageScope, key: String): JsonElement? = storage[k(extensionId, scope, key)]
            override suspend fun set(extensionId: String, scope: StorageScope, key: String, value: JsonElement) {
                storage[k(extensionId, scope, key)] = value
            }
            override suspend fun delete(extensionId: String, scope: StorageScope, key: String) { storage.remove(k(extensionId, scope, key)) }
            override suspend fun keys(extensionId: String, scope: StorageScope): List<String> =
                storage.keys.filter { it.startsWith("$extensionId/${scope.wire}/") }.map { it.substringAfterLast('/') }
            override suspend fun usedBytes(extensionId: String): Long = storage.entries.filter { it.key.startsWith("$extensionId/") }
                .sumOf { StoragePort.entryBytes(it.key.substringAfterLast('/'), it.value) }
        },
        secrets = object : SecretPort {
            override suspend fun get(extensionId: String, name: String): String? = if (name == "token") "s3cret" else null
        },
        clipboard = object : ClipboardPort {
            override suspend fun read(): String? = clipboard
            override suspend fun write(text: String) { clipboard = text }
        },
    )

    fun stored(id: String, key: String): JsonElement? = storage["$id/global/$key"]
}

/** `{"v":1,"id":7,"fn":fn,"args":args}` as the single argument of `test.proxy`. */
fun proxyArgs(fn: String, args: JsonObject = JsonObject(emptyMap())): JsonArray = JsonArray(
    listOf(buildJsonObject { put("v", 1); put("id", 7); put("fn", fn); put("args", args) }),
)

val EMPTY = JsonObject(emptyMap())
val NULL: JsonElement = JsonNull
