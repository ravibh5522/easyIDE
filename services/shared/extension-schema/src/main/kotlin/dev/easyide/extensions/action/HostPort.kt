package dev.easyide.extensions.action

import dev.easyide.extensions.manifest.ExtensionId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Everything an action touches outside this module, implemented by `:app` (terminal tabs,
 * editor, prompts, settings writes, LSP, stages) and `:sandbox-runtime` (processes). Split
 * by concern so an adapter can implement one piece; [HostPort] is the whole set the
 * [ActionRunner] needs. Every call here is a real boundary: implementations handle their
 * own I/O failures and report them through the typed outcomes, never by throwing.
 *
 * All paths crossing these ports are guest paths (`/workspace/...`).
 */
interface HostPort : WorkspacePort, TerminalPort, ProcessPort, TaskPort, EditorPort, PromptPort, UiPort, LspPort, CommandPort

/** Editor state frozen at invocation (extension-runtime.md sec 8.2); positions are 1-based. */
data class EditorState(
    val path: String?, val languageId: String?, val line: Int, val column: Int,
    val selectedText: String, val currentWord: String, val lineText: String,
)

/** [folder] is the project root inside the guest; [name] the project name. */
data class WorkspaceState(val folder: String, val name: String, val envId: String?, val envName: String?) {
    companion object { const val GUEST_WORKSPACE = "/workspace" }
}

interface WorkspacePort {
    fun workspace(): WorkspaceState
    fun activeEditor(): EditorState?

    /**
     * The environment's configured shell env (`GuestEnvironment.defaults` plus `terminal.env`)
     * for `${env:NAME}`. Never the Android process env (R-SEC-04).
     */
    suspend fun shellEnvironment(envId: String): Map<String, String>
}

/** [commandLine] is complete and already quoted: `cd '<cwd>' && K='v' <command>`. */
data class TerminalRequest(val owner: ExtensionId, val terminalName: String, val commandLine: String, val focus: Boolean, val clear: Boolean)

interface TerminalPort {
    /** Reuses the tab this owner opened under [TerminalRequest.terminalName], else opens one; returns once written. */
    suspend fun runInTerminal(request: TerminalRequest)
}

/** A process in the environment, argv form (no shell). Limits come from settings via the runner. */
data class ExecRequest(
    val owner: ExtensionId, val envId: String, val title: String, val argv: List<String>, val cwd: String?,
    val env: Map<String, String>, val output: ExecOutput, val captureLimitBytes: Int, val timeoutMs: Long,
)

sealed interface ExecOutcome {
    /** Streams are empty unless [ExecOutput.CAPTURE]; [truncated] when a stream hit the capture cap. */
    data class Exited(val exitCode: Int, val stdout: String, val stderr: String, val truncated: Boolean) : ExecOutcome
    /** The port killed the process at the timeout. */
    data class TimedOut(val stderrTail: String) : ExecOutcome
    /** Could not start (environment stopped, spawn failure). */
    data class Unavailable(val reason: String) : ExecOutcome
}

interface ProcessPort {
    suspend fun exec(request: ExecRequest): ExecOutcome
}

sealed interface ResolvedTask {
    data class Label(val label: String) : ResolvedTask
    data class Definition(val definition: JsonElement) : ResolvedTask
}

sealed interface TaskOutcome {
    data class Exited(val exitCode: Int) : TaskOutcome
    data object NotFound : TaskOutcome
    data class Unavailable(val reason: String) : TaskOutcome
}

interface TaskPort {
    /** Resolves the task (`.easyide/tasks.json`, contributed definitions) and runs it in a terminal with problem matchers. */
    suspend fun runTask(owner: ExtensionId, envId: String, task: ResolvedTask): TaskOutcome
}

/** A text edit after substitution; [path] is an absolute guest path. */
data class ResolvedTextEdit(val path: String, val range: TextRange, val text: String)

interface EditorPort {
    /** False when the file does not exist. */
    suspend fun openFile(path: String, line: Int?, column: Int?, preview: Boolean): Boolean
    suspend fun applyEdits(edits: List<ResolvedTextEdit>): Boolean
    suspend fun applyWorkspaceEdit(edit: JsonObject): Boolean

    /** Inserts into the active editor; false when there is none or a named snippet is unknown. */
    suspend fun insertSnippet(body: String?, name: String?, language: String?): Boolean
}

data class PickItem(val label: String, val description: String?, val value: JsonElement)
data class QuickPickRequest(val title: String, val items: List<PickItem>, val placeHolder: String?, val canPickMany: Boolean)
data class InputBoxRequest(val title: String, val prompt: String?, val value: String?, val placeHolder: String?, val validate: Regex?, val password: Boolean)
data class MessageRequest(val owner: ExtensionId, val text: String, val severity: MessageSeverity, val actions: List<String>)

interface PromptPort {
    /** Picked values, or null when the user dismissed the picker (a silent cancel). */
    suspend fun quickPick(request: QuickPickRequest): List<JsonElement>?
    suspend fun inputBox(request: InputBoxRequest): String?

    /** The chosen action title, or null when dismissed or there were none. */
    suspend fun showMessage(request: MessageRequest): String?

    /** The confirm sheet showing the full URL before anything opens it (R-SEC-11). */
    suspend fun confirmUrl(url: String): Boolean
}

interface UiPort {
    suspend fun openUrl(url: String)
    suspend fun revealStage(stage: String, view: String?, focus: Boolean)

    /** Opens the extension document [uri] (already checked to be the owner's); false when no shell can show it. */
    suspend fun openDocument(uri: String, group: OpenGroup, preview: Boolean): Boolean
}

sealed interface LspOutcome {
    data class Result(val value: JsonElement) : LspOutcome
    data class Unavailable(val reason: String) : LspOutcome
    data class Failed(val message: String) : LspOutcome
}

interface LspPort {
    /** [params] null = position params of the caret; [then] is applied by the app to the result. */
    suspend fun lspRequest(owner: ExtensionId, language: String, method: String, params: JsonElement?, then: LspThen): LspOutcome
}

sealed interface CommandOutcome {
    data class Done(val value: JsonElement) : CommandOutcome
    data object Cancelled : CommandOutcome
    data object NotFound : CommandOutcome
    /** [error] is the sdk-reference code the Extension Log shows (an L2 handler's own code, e.g. E_CAPABILITY). */
    data class Failed(val message: String, val error: ActionError = ActionError.INTERNAL) : CommandOutcome
}

interface CommandPort {
    /** Runs a command the app itself owns (built-ins); extension commands never reach here. */
    suspend fun executeBuiltIn(commandId: String, args: JsonElement?): CommandOutcome
}

/**
 * L2 (WASM) command handlers, provided by `:ext-wasm` (hld.md: `:extensions` sees WASM only
 * through this port). The runtime activates the extension before calling it.
 */
fun interface LogicHost {
    suspend fun executeCommand(owner: ExtensionId, commandId: String, args: JsonElement?): CommandOutcome
}
