package dev.easyide.app.extensions.wasm

import dev.easyide.app.extensions.host.ActiveDocument
import dev.easyide.app.extensions.host.WorkspaceBridge
import dev.easyide.extensions.action.CommandOutcome
import dev.easyide.extensions.action.EditorState
import dev.easyide.extensions.action.ExecOutcome
import dev.easyide.extensions.action.LspOutcome
import dev.easyide.extensions.action.LspThen
import dev.easyide.extensions.action.ResolvedTask
import dev.easyide.extensions.action.ResolvedTextEdit
import dev.easyide.extensions.action.TaskOutcome
import dev.easyide.extensions.action.TerminalRequest
import dev.easyide.extensions.action.WorkspaceState
import dev.easyide.extwasm.ActivationContext
import dev.easyide.extwasm.EnvInfo
import dev.easyide.extwasm.HostResult
import dev.easyide.extwasm.SettingsLookup
import dev.easyide.extwasm.WasmHost
import dev.easyide.extwasm.host.HostPorts
import dev.easyide.extwasm.host.WasmExtension
import dev.easyide.extwasm.load.WasmModuleLoader
import dev.easyide.extwasm.testing.EMPTY
import dev.easyide.extwasm.testing.FakePorts
import dev.easyide.extwasm.testing.WasmFixtures
import dev.easyide.extwasm.testing.proxyArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import java.io.File

/** `:ext-wasm`'s compiled `.wat` fixtures (the build points `easyide.wasmFixtures` at them). */
object AppWasmFixtures : WasmFixtures({ name ->
    File(checkNotNull(System.getProperty("easyide.wasmFixtures")) { "easyide.wasmFixtures not set by the build" }, name).readBytes()
})

/** The Rust sample, read from disk like the other app tests read shared files. */
val WORD_COUNT_SAMPLE = File("../../shared/samples/wasm-word-count")

/** A workspace with one active editor; records edits and selections. */
class FakeBridge(
    override val projectDirectory: File? = null,
    var document: ActiveDocument? = ActiveDocument("/workspace/notes.md", "markdown", 1, "one two three", 0, 0, true),
) : WorkspaceBridge {
    override val environmentId = "env1"
    val edits = ArrayList<List<ResolvedTextEdit>>()
    val selected = ArrayList<Triple<String, Int, Int>>()
    var lspAnswer: LspOutcome = LspOutcome.Result(JsonNull)

    override fun activeDocument(): ActiveDocument? = document
    override suspend fun select(path: String, start: Int, end: Int): Boolean { selected += Triple(path, start, end); return document?.path == path }
    override fun workspaceState() = WorkspaceState("/workspace", "demo", environmentId, null)
    override fun editorState(): EditorState? = null
    override suspend fun runInTerminal(request: TerminalRequest) = Unit
    override suspend fun runCommandTerminal(title: String, argv: List<String>, cwd: String?, env: Map<String, String>, timeoutMs: Long): ExecOutcome =
        ExecOutcome.Exited(0, "", "", false)
    override suspend fun startCaptured(argv: List<String>, cwd: String?, env: Map<String, String>): Process = error("no processes in tests")
    override suspend fun openFile(path: String, line: Int?, column: Int?) = false
    override suspend fun applyEdits(edits: List<ResolvedTextEdit>): Boolean { this.edits += edits; return true }
    override suspend fun insertSnippet(body: String?, name: String?, language: String?) = false
    override fun revealStage(stage: String, focus: Boolean) = false
    override suspend fun executeBuiltIn(commandId: String, args: JsonElement?): CommandOutcome = CommandOutcome.NotFound
    override suspend fun runTask(task: ResolvedTask): TaskOutcome = TaskOutcome.NotFound
    override suspend fun lspRequest(language: String, method: String, params: JsonElement?, then: LspThen): LspOutcome = lspAnswer
}

/**
 * The real [WasmHost] running `:ext-wasm`'s `proxy` guest, whose `test.proxy` command forwards
 * one host call, so an app-side port is exercised behind the host's own capability checks.
 */
class ProxyHarness(dir: File, ports: HostPorts, settings: SettingsLookup, scope: CoroutineScope) {
    val host = WasmHost(WasmModuleLoader(File(dir, "cache")), ports, settings, { _, _ -> }, scope)

    fun extension(dir: File, caps: List<String>): WasmExtension = AppWasmFixtures.extension(dir, caps = caps)

    fun activate(ext: WasmExtension) {
        val r = runBlocking { host.activate(ext, ActivationContext("0.3.0", EMPTY, EnvInfo("env1", "debian", "arm64"))) }
        assertTrue("activation failed: $r", r is HostResult.Ok)
    }

    /** The host's reply to one guest call: `{ok, result}` or `{ok:false, error:{code, message}}`. */
    fun call(ext: WasmExtension, fn: String, args: JsonObject = EMPTY): JsonObject {
        val r = runBlocking { host.executeCommand(ext.id, "test.proxy", proxyArgs(fn, args), EMPTY) }
        assertTrue("proxy call failed: $r", r is HostResult.Ok)
        return (r as HostResult.Ok).result!!.jsonObject
    }
}

fun JsonObject.errorCode(): String? = (this["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.content

/** These fake ports with the given ones swapped in for the real app ports under test. */
fun FakePorts.with(
    storage: dev.easyide.extwasm.host.StoragePort = ports.storage,
    config: dev.easyide.extwasm.host.ConfigPort = ports.config,
    files: dev.easyide.extwasm.host.FilePort = ports.files,
): HostPorts = HostPorts(
    ports.info, ports.log, ports.editor, files, ports.ui, ports.commands, config, ports.lsp,
    ports.sandbox, ports.net, storage, ports.secrets, ports.clipboard,
)
