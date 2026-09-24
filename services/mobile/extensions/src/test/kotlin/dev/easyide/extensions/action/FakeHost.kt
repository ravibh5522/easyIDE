package dev.easyide.extensions.action

import dev.easyide.extensions.manifest.ExtensionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Records every port call; responses are programmable per test. */
class FakeHost : HostPort {
    var workspace = WorkspaceState("/workspace", "demo", "env1", "Debian")
    var editor: EditorState? = EditorState("/workspace/src/my file.py", "python", 3, 7, "sel", "word", "line text")
    var shellEnv = mapOf("HOME" to "/root", "VIRTUAL_ENV" to "/workspace/.venv")

    val terminal = ArrayList<TerminalRequest>()
    val execs = ArrayList<ExecRequest>()
    var execResult: (ExecRequest) -> ExecOutcome = { ExecOutcome.Exited(0, "", "", false) }
    /** When set, exec suspends until completed (single-flight and cancellation tests). */
    var execGate: CompletableDeferred<Unit>? = null
    val tasks = ArrayList<ResolvedTask>()
    var taskOutcome: TaskOutcome = TaskOutcome.Exited(0)
    val opened = ArrayList<String>()
    var fileExists = true
    val edits = ArrayList<List<ResolvedTextEdit>>()
    val workspaceEdits = ArrayList<JsonObject>()
    val snippets = ArrayList<Triple<String?, String?, String?>>()
    val picks = ArrayList<QuickPickRequest>()
    var pickAnswer: (QuickPickRequest) -> List<JsonElement>? = { r -> r.items.take(1).map { it.value } }
    val inputs = ArrayList<InputBoxRequest>()
    var inputAnswer: (InputBoxRequest) -> String? = { "typed" }
    val messages = ArrayList<MessageRequest>()
    var messageAnswer: (MessageRequest) -> String? = { null }
    var confirmUrlAnswer = true
    val urls = ArrayList<String>()
    val stages = ArrayList<String>()
    val lsp = ArrayList<Triple<String, String, JsonElement?>>()
    var lspOutcome: LspOutcome = LspOutcome.Result(JsonNull)
    val builtIns = ArrayList<Pair<String, JsonElement?>>()
    var builtInOutcome: (String) -> CommandOutcome = { CommandOutcome.Done(JsonNull) }

    override fun workspace() = workspace
    override fun activeEditor() = editor
    override suspend fun shellEnvironment(envId: String) = shellEnv
    override suspend fun runInTerminal(request: TerminalRequest) { terminal += request }
    override suspend fun exec(request: ExecRequest): ExecOutcome {
        execs += request
        execGate?.await()
        return execResult(request)
    }
    override suspend fun runTask(owner: ExtensionId, envId: String, task: ResolvedTask): TaskOutcome { tasks += task; return taskOutcome }
    override suspend fun openFile(path: String, line: Int?, column: Int?, preview: Boolean): Boolean { opened += path; return fileExists }
    override suspend fun applyEdits(edits: List<ResolvedTextEdit>): Boolean { this.edits += edits; return true }
    override suspend fun applyWorkspaceEdit(edit: JsonObject): Boolean { workspaceEdits += edit; return true }
    override suspend fun insertSnippet(body: String?, name: String?, language: String?): Boolean { snippets += Triple(body, name, language); return true }
    override suspend fun quickPick(request: QuickPickRequest): List<JsonElement>? { picks += request; return pickAnswer(request) }
    override suspend fun inputBox(request: InputBoxRequest): String? { inputs += request; return inputAnswer(request) }
    override suspend fun showMessage(request: MessageRequest): String? { messages += request; return messageAnswer(request) }
    override suspend fun confirmUrl(url: String): Boolean = confirmUrlAnswer
    override suspend fun openUrl(url: String) { urls += url }
    override suspend fun revealStage(stage: String, view: String?, focus: Boolean) { stages += stage }
    override suspend fun lspRequest(owner: ExtensionId, language: String, method: String, params: JsonElement?, then: LspThen): LspOutcome {
        lsp += Triple(language, method, params)
        return lspOutcome
    }
    override suspend fun executeBuiltIn(commandId: String, args: JsonElement?): CommandOutcome {
        builtIns += commandId to args
        return builtInOutcome(commandId)
    }
}
