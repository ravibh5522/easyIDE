package dev.easyide.app.extensions.host

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
import kotlinx.serialization.json.JsonElement

/**
 * What the open workspace offers the extension host: its terminals, editor buffers,
 * stages and command registry. Implemented by the workspace screen's controller and
 * attached to [AppHostPort] while that workspace is open; with none attached every
 * workspace-bound action reports itself unavailable instead of guessing a project.
 *
 * Paths are guest paths (`/workspace/...`) on this side, like every port in :extensions.
 */
interface WorkspaceBridge {
    val environmentId: String

    fun workspaceState(): WorkspaceState
    fun editorState(): EditorState?

    /** Reuses the tab [TerminalRequest.owner] opened under that name, else opens one; writes the line. */
    suspend fun runInTerminal(request: TerminalRequest)

    /**
     * Runs [argv] in a new command terminal tab titled [title] and waits for its exit
     * (killed at [timeoutMs]). Stream contents stay in the tab, so the outcome's
     * streams are empty.
     */
    suspend fun runCommandTerminal(title: String, argv: List<String>, cwd: String?, env: Map<String, String>, timeoutMs: Long): ExecOutcome

    /**
     * A process with separate pipes in this workspace's environment.
     * @throws Exception (SandboxError.EnvironmentNotReady, IOException) when it cannot start.
     */
    suspend fun startCaptured(argv: List<String>, cwd: String?, env: Map<String, String>): Process

    /** False when the file does not exist in the project. */
    suspend fun openFile(path: String, line: Int?, column: Int?): Boolean

    /** Applies all [edits] or none; false when a range is invalid or a file cannot be edited. */
    suspend fun applyEdits(edits: List<ResolvedTextEdit>): Boolean

    /** Inserts a snippet at the caret of the active editor; false without one or for an unknown name. */
    suspend fun insertSnippet(body: String?, name: String?, language: String?): Boolean

    /** Shows one of the built-in stages; false for a stage this workspace does not have. */
    fun revealStage(stage: String, focus: Boolean): Boolean

    suspend fun executeBuiltIn(commandId: String, args: JsonElement?): CommandOutcome

    suspend fun runTask(task: ResolvedTask): TaskOutcome

    /**
     * An extension's request to this workspace's language servers (sdk-reference `lspRequest`);
     * [params] null = position params of the caret; [then] is applied here.
     */
    suspend fun lspRequest(language: String, method: String, params: JsonElement?, then: LspThen): LspOutcome
}
