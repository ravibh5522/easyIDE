package dev.easyide.app.extensions.host

import dev.easyide.extensions.action.CommandOutcome
import dev.easyide.extensions.action.EditorState
import dev.easyide.extensions.action.ExecOutcome
import dev.easyide.extensions.action.ExecOutput
import dev.easyide.extensions.action.ExecRequest
import dev.easyide.extensions.action.ExtensionLog
import dev.easyide.extensions.action.HostPort
import dev.easyide.extensions.action.InputBoxRequest
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.action.LogLevel
import dev.easyide.extensions.action.LspOutcome
import dev.easyide.extensions.action.LspThen
import dev.easyide.extensions.action.MessageRequest
import dev.easyide.extensions.action.QuickPickRequest
import dev.easyide.extensions.action.ResolvedTask
import dev.easyide.extensions.action.ResolvedTextEdit
import dev.easyide.extensions.action.TaskOutcome
import dev.easyide.extensions.action.TerminalRequest
import dev.easyide.extensions.action.WorkspaceState
import dev.easyide.extensions.manifest.ExtensionId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException

/** Opens an https URL outside the app (the external browser); false when nothing can handle it. */
fun interface UrlOpener {
    fun open(url: String): Boolean
}

/**
 * The runtime's [HostPort]: prompts go to [ui], workspace-bound actions (terminals,
 * editor, stages, built-in commands, processes) go to the attached [WorkspaceBridge].
 * Only one workspace is open at a time; with none attached those actions report
 * `Unavailable`/false, never act on a guessed project.
 */
class AppHostPort(
    private val ui: ExtensionUiHost,
    private val urls: UrlOpener,
    private val shellEnvironment: () -> Map<String, String>,
    private val log: ExtensionLog,
    private val io: CoroutineDispatcher,
) : HostPort {

    @Volatile private var bridge: WorkspaceBridge? = null

    fun attach(workspace: WorkspaceBridge) { bridge = workspace }

    /** The open workspace, for the WASM ports (editor, files, processes act only on it). */
    val workspaceBridge: WorkspaceBridge? get() = bridge

    /** Detaches only if [workspace] is still the attached one (a newer screen may have replaced it). */
    fun detach(workspace: WorkspaceBridge) { if (bridge === workspace) bridge = null }

    // ---- WorkspacePort

    override fun workspace(): WorkspaceState = bridge?.workspaceState() ?: NO_WORKSPACE

    override fun activeEditor(): EditorState? = bridge?.editorState()

    override suspend fun shellEnvironment(envId: String): Map<String, String> = shellEnvironment()

    // ---- TerminalPort, ProcessPort, TaskPort

    override suspend fun runInTerminal(request: TerminalRequest) {
        val b = bridge
        if (b == null) {
            log.append(LogEntry(request.owner, LogLevel.WARN, "runInTerminal: no workspace is open"))
            return
        }
        b.runInTerminal(request)
    }

    override suspend fun exec(request: ExecRequest): ExecOutcome {
        val b = bridge?.takeIf { it.environmentId == request.envId }
            ?: return ExecOutcome.Unavailable("environment ${request.envId} is not open in a workspace")
        if (request.output == ExecOutput.TERMINAL) {
            return b.runCommandTerminal(request.title, request.argv, request.cwd, request.env, request.timeoutMs)
        }
        // Process-spawn boundary: a not-ready environment, proot preparation (SandboxError) and exec (IOException) land here.
        val process = try {
            b.startCaptured(request.argv, request.cwd, request.env)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ExecOutcome.Unavailable("cannot start ${request.argv.firstOrNull()}: ${e.message}")
        }
        return ProcessCapture.run(process, request.captureLimitBytes, request.timeoutMs, keep = request.output == ExecOutput.CAPTURE, io)
    }

    override suspend fun runTask(owner: ExtensionId, envId: String, task: ResolvedTask): TaskOutcome {
        val b = bridge?.takeIf { it.environmentId == envId } ?: return TaskOutcome.Unavailable("no workspace is open for $envId")
        return b.runTask(task)
    }

    // ---- EditorPort

    override suspend fun openFile(path: String, line: Int?, column: Int?, preview: Boolean): Boolean =
        bridge?.openFile(path, line, column) ?: false

    override suspend fun applyEdits(edits: List<ResolvedTextEdit>): Boolean = bridge?.applyEdits(edits) ?: false

    override suspend fun applyWorkspaceEdit(edit: JsonObject): Boolean {
        val edits = TextEdits.fromWorkspaceEdit(edit)
        if (edits == null) {
            log.append(LogEntry(null, LogLevel.WARN, "workspace edit refused: file create/rename/delete operations are not applied"))
            return false
        }
        return bridge?.applyEdits(edits) ?: false
    }

    override suspend fun insertSnippet(body: String?, name: String?, language: String?): Boolean =
        bridge?.insertSnippet(body, name, language) ?: false

    // ---- PromptPort

    override suspend fun quickPick(request: QuickPickRequest): List<JsonElement>? = ui.show(UiPrompt.QuickPick(request))

    override suspend fun inputBox(request: InputBoxRequest): String? = ui.show(UiPrompt.InputBox(request))

    override suspend fun showMessage(request: MessageRequest): String? {
        if (request.actions.isEmpty()) {
            ui.notify(request.text)
            return null
        }
        return ui.show(UiPrompt.Message(request))
    }

    override suspend fun confirmUrl(url: String): Boolean = ui.show(UiPrompt.ConfirmUrl(url))

    // ---- UiPort

    override suspend fun openUrl(url: String) {
        if (!urls.open(url)) log.append(LogEntry(null, LogLevel.WARN, "no app can open $url"))
    }

    override suspend fun revealStage(stage: String, view: String?, focus: Boolean) {
        val shown = bridge?.revealStage(stage, focus) ?: false
        if (!shown) log.append(LogEntry(null, LogLevel.WARN, "revealStage: '$stage' is not available in this workspace"))
    }

    // ---- LspPort, CommandPort

    /** Servers are per workspace; with none open there is nothing to ask (typed, not thrown). */
    override suspend fun lspRequest(owner: ExtensionId, language: String, method: String, params: JsonElement?, then: LspThen): LspOutcome =
        bridge?.lspRequest(language, method, params, then) ?: LspOutcome.Unavailable("no workspace is open for a $language server")

    override suspend fun executeBuiltIn(commandId: String, args: JsonElement?): CommandOutcome =
        bridge?.executeBuiltIn(commandId, args) ?: CommandOutcome.NotFound

    private companion object {
        val NO_WORKSPACE = WorkspaceState(WorkspaceState.GUEST_WORKSPACE, "", null, null)
    }
}
