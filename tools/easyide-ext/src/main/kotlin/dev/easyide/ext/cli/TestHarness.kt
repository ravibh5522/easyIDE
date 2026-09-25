package dev.easyide.ext.cli

import dev.easyide.extensions.action.ActionOutcome
import dev.easyide.extensions.action.ActionRunner
import dev.easyide.extensions.action.CommandBinding
import dev.easyide.extensions.action.CommandHandler
import dev.easyide.extensions.action.CommandOutcome
import dev.easyide.extensions.action.EditorState
import dev.easyide.extensions.action.ExecOutcome
import dev.easyide.extensions.action.ExecRequest
import dev.easyide.extensions.action.HostPort
import dev.easyide.extensions.action.InputBoxRequest
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.action.LogLevel
import dev.easyide.extensions.action.LspOutcome
import dev.easyide.extensions.action.LspThen
import dev.easyide.extensions.action.OpenGroup
import dev.easyide.extensions.action.MessageRequest
import dev.easyide.extensions.action.QuickPickRequest
import dev.easyide.extensions.action.ResolvedTask
import dev.easyide.extensions.action.ResolvedTextEdit
import dev.easyide.extensions.action.TaskOutcome
import dev.easyide.extensions.action.TerminalRequest
import dev.easyide.extensions.action.WorkspaceState
import dev.easyide.extensions.json.jsonEquals
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.settings.ConfigTarget
import dev.easyide.extensions.settings.SettingsPort
import dev.easyide.extensions.settings.SettingsQuery
import dev.easyide.extensions.whenclause.ContextKeyService
import dev.easyide.extensions.whenclause.ContextLookup
import dev.easyide.extensions.whenclause.WhenEvaluator
import dev.easyide.extensions.whenclause.WhenExpr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * cli.md sec 5.6: replays the scenario files in `test/` against a pack with the app's own
 * `ActionRunner`, capability checks and when-clause evaluator, on a recording host that
 * answers prompts and processes from the scenario's `fakes`. No device, no environment.
 */
class TestHarness(private val d: ExtensionDescriptor) {

    data class Outcome(val name: String, val passed: Boolean, val problems: List<String>, val recorded: List<JsonObject>)

    fun run(scenario: JsonObject, fallbackName: String): Outcome {
        val name = scenario["name"]?.stringOrNull ?: fallbackName
        val settings = ScenarioSettings(d, scenario.obj("settings"))
        val host = RecordingHost(scenario.obj("editor"), scenario.obj("fakes"), settings)
        val keys = ContextKeyService(settings)
        // A scenario starts in a running environment; `context` overrides (e.g. envState "stopped").
        keys.setRaw("envState", JsonPrimitive("ready"))
        scenario.obj("context").forEach { (k, v) -> keys.setRaw(k, v) }
        host.editor?.let { e -> if (scenario.obj("context")["editorLangId"] == null) keys.setRaw("editorLangId", JsonPrimitive(e.languageId)) }
        val log = ArrayList<LogEntry>()
        val bindings = d.actions.mapValues { (cmd, action) ->
            val title = d.contributes.commands.firstOrNull { it.command == cmd }?.title ?: cmd
            CommandBinding(d.id, cmd, title, CommandHandler.Declarative(action), d.inputs, d.capabilities,
                d.contributes.configuration.mapTo(HashSet()) { it.key }, d.guestRoot)
        }
        val runner = ActionRunner({ bindings[it] }, host, settings, { keys.snapshot.value }, { log += it }) { _, id, _ ->
            CommandOutcome.Failed("$id is implemented in WASM; the test harness runs L1 actions only")
        }
        val errors = ArrayList<String>()
        for (step in scenario.arr("steps")) {
            val o = step as? JsonObject ?: continue
            val command = o["execute"]?.stringOrNull ?: continue
            when (val out = runBlocking { runner.run(command, o["args"]) }) {
                is ActionOutcome.Failed -> errors += "${out.error.name}: ${out.message}"
                else -> Unit
            }
        }
        errors += log.filter { it.level == LogLevel.ERROR }.map { it.message }.filter { it !in errors }

        val expect = scenario.obj("expect")
        val problems = ArrayList<String>()
        expect["visible"]?.let { want ->
            val visible = visibleRefs(keys.snapshot.value)
            (want as? JsonArray)?.mapNotNull { it.stringOrNull }?.forEach { if (it !in visible) problems += "not visible: $it (visible: ${visible.sorted()})" }
        }
        expect["hidden"]?.let { want ->
            val visible = visibleRefs(keys.snapshot.value)
            (want as? JsonArray)?.mapNotNull { it.stringOrNull }?.forEach { if (it in visible) problems += "visible but expected hidden: $it" }
        }
        (expect["views"] as? JsonArray)?.let { problems += ViewScenario.check(d, it) }
        (expect["calls"] as? JsonArray)?.let { want -> problems += subsequence(want.filterIsInstance<JsonObject>(), host.calls) }
        (expect["messages"] as? JsonArray)?.let { want ->
            val got = host.calls.filter { it["type"]?.stringOrNull == "showMessage" }.map { it["text"]?.stringOrNull }
            val wanted = want.mapNotNull { it.stringOrNull }
            if (got != wanted) problems += "messages: expected $wanted, got $got"
        }
        (expect["errors"] as? JsonArray)?.let { want ->
            val wanted = want.mapNotNull { it.stringOrNull }
            if (wanted.isEmpty() && errors.isNotEmpty()) problems += "unexpected errors: $errors"
            wanted.forEach { w -> if (errors.none { it.contains(w) }) problems += "expected an error containing '$w', got $errors" }
        } ?: if (errors.isNotEmpty()) problems += "unexpected errors: $errors" else Unit
        return Outcome(name, problems.isEmpty(), problems, host.calls)
    }

    /** Refs (`menu:<id>:<command>`, `keybinding:<command>`, `statusBar:<id>`, `keyRow:<id>`) whose `when` holds now. */
    private fun visibleRefs(ctx: ContextLookup): Set<String> {
        fun holds(w: WhenExpr?) = w == null || WhenEvaluator.evaluate(w, ctx)
        val c = d.contributes
        return buildSet {
            c.menus.filter { holds(it.`when`) }.forEach { add("menu:${it.menuId}:${it.command}") }
            c.keybindings.filter { holds(it.`when`) }.forEach { add("keybinding:${it.command}") }
            c.statusBarItems.filter { holds(it.`when`) }.forEach { add("statusBar:${it.id}") }
            c.keyRows.filter { holds(it.`when`) }.forEach { add("keyRow:${it.id}") }
        }
    }

    /** Each expected call must match a later recorded one (its keys a subset of the record's), in order. */
    private fun subsequence(want: List<JsonObject>, got: List<JsonObject>): List<String> {
        var at = 0
        for (w in want) {
            val i = (at until got.size).firstOrNull { idx -> w.all { (k, v) -> got[idx][k]?.let { jsonEquals(it, v) } == true } }
                ?: return listOf("call not made (in order): $w; recorded: $got")
            at = i + 1
        }
        return emptyList()
    }
}

private fun JsonObject.obj(k: String): JsonObject = this[k] as? JsonObject ?: JsonObject(emptyMap())
private fun JsonObject.arr(k: String): JsonArray = this[k] as? JsonArray ?: JsonArray(emptyList())
private fun s(v: String?) = v?.let(::JsonPrimitive) ?: JsonNull
private fun rec(type: String, vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf("type" to JsonPrimitive(type)) + fields)

/** Scenario settings over the pack's contributed defaults; writes are recorded as `setConfig` calls. */
private class ScenarioSettings(d: ExtensionDescriptor, initial: JsonObject) : SettingsPort {
    val values = HashMap<String, JsonElement>().apply {
        d.contributes.configuration.forEach { p -> p.default?.let { put(p.key, it) } }
        putAll(initial)
    }
    var onWrite: (String, JsonElement?, ConfigTarget) -> Unit = { _, _, _ -> }
    private val versionFlow = MutableStateFlow(0L)
    override val version: StateFlow<Long> get() = versionFlow
    override fun value(key: String, query: SettingsQuery): JsonElement? = values[key]
    override fun profileExtensions(): Set<String>? = null
    override suspend fun write(key: String, value: JsonElement?, target: ConfigTarget, query: SettingsQuery) {
        if (value == null) values.remove(key) else values[key] = value
        versionFlow.value++
        onWrite(key, value, target)
    }
}

/** The host every step talks to: records each call and answers from `fakes`. */
private class RecordingHost(editorFixture: JsonObject, fakes: JsonObject, settings: ScenarioSettings) : HostPort {
    val calls = ArrayList<JsonObject>()
    val editor: EditorState? = editorFixture["path"]?.stringOrNull?.let { path ->
        val text = editorFixture["text"]?.stringOrNull.orEmpty()
        val lang = editorFixture["languageId"]?.stringOrNull ?: languageOf(path)
        EditorState(path, lang, 0, 0, "", "", text.lineSequence().firstOrNull().orEmpty())
    }
    private val execFakes = fakes.arr("sandboxExec").filterIsInstance<JsonObject>()
    private val picks = ArrayDeque(fakes.arr("quickPick").toList())
    private val inputs = ArrayDeque(fakes.arr("inputBox").mapNotNull { it.stringOrNull })

    init {
        settings.onWrite = { k, v, t -> calls += rec("setConfig", "key" to JsonPrimitive(k), "value" to (v ?: JsonNull), "target" to JsonPrimitive(t.wire)) }
    }

    override fun workspace() = WorkspaceState("/workspace", "workspace", "test-env", "Ubuntu")
    override fun activeEditor() = editor
    override suspend fun shellEnvironment(envId: String) = mapOf("HOME" to "/root", "PATH" to "/usr/local/bin:/usr/bin:/bin")
    override suspend fun runInTerminal(request: TerminalRequest) {
        calls += rec("runInTerminal", "terminal" to JsonPrimitive(request.terminalName), "command" to JsonPrimitive(request.commandLine))
    }
    override suspend fun exec(request: ExecRequest): ExecOutcome {
        calls += rec("sandboxExec", "argv" to JsonArray(request.argv.map(::JsonPrimitive)), "cwd" to s(request.cwd))
        val fake = execFakes.firstOrNull { f -> matches(f["match"] as? JsonArray, request.argv) }
            ?: return ExecOutcome.Unavailable("no fakes.sandboxExec entry matches ${request.argv}")
        return ExecOutcome.Exited((f(fake, "exitCode") ?: "0").toInt(), f(fake, "stdout").orEmpty(), f(fake, "stderr").orEmpty(), false)
    }
    override suspend fun runTask(owner: ExtensionId, envId: String, task: ResolvedTask): TaskOutcome {
        calls += rec("runTask", "task" to JsonPrimitive(task.toString())); return TaskOutcome.Exited(0)
    }
    override suspend fun openFile(path: String, line: Int?, column: Int?, preview: Boolean): Boolean { calls += rec("openFile", "path" to JsonPrimitive(path)); return true }
    override suspend fun applyEdits(edits: List<ResolvedTextEdit>): Boolean {
        calls += rec("applyEdits", "edits" to JsonArray(edits.map { JsonPrimitive("${it.path}:${it.range}=${it.text}") })); return true
    }
    override suspend fun applyWorkspaceEdit(edit: JsonObject): Boolean { calls += rec("applyWorkspaceEdit", "edit" to edit); return true }
    override suspend fun insertSnippet(body: String?, name: String?, language: String?): Boolean {
        calls += rec("insertSnippet", "body" to s(body), "name" to s(name)); return true
    }
    override suspend fun quickPick(request: QuickPickRequest): List<JsonElement>? {
        calls += rec("showQuickPick", "items" to JsonArray(request.items.map { JsonPrimitive(it.label) }))
        val answer = picks.removeFirstOrNull() ?: return null
        return listOf(request.items.firstOrNull { jsonEquals(it.value, answer) || it.label == answer.stringOrNull }?.value ?: answer)
    }
    override suspend fun inputBox(request: InputBoxRequest): String? { calls += rec("showInputBox", "prompt" to s(request.prompt)); return inputs.removeFirstOrNull() }
    override suspend fun showMessage(request: MessageRequest): String? {
        calls += rec("showMessage", "text" to JsonPrimitive(request.text), "severity" to JsonPrimitive(request.severity.name.lowercase())); return null
    }
    override suspend fun confirmUrl(url: String): Boolean = true
    override suspend fun openUrl(url: String) { calls += rec("openUrl", "url" to JsonPrimitive(url)) }
    override suspend fun revealStage(stage: String, view: String?, focus: Boolean) { calls += rec("revealStage", "stage" to JsonPrimitive(stage)) }
    override suspend fun openDocument(uri: String, group: OpenGroup, preview: Boolean): Boolean {
        calls += rec("openDocument", "uri" to JsonPrimitive(uri), "group" to JsonPrimitive(group.wire)); return true
    }
    override suspend fun lspRequest(owner: ExtensionId, language: String, method: String, params: JsonElement?, then: LspThen): LspOutcome {
        calls += rec("lspRequest", "language" to JsonPrimitive(language), "method" to JsonPrimitive(method)); return LspOutcome.Unavailable("no language server in tests")
    }
    override suspend fun executeBuiltIn(commandId: String, args: JsonElement?): CommandOutcome {
        calls += rec("executeCommand", "command" to JsonPrimitive(commandId)); return CommandOutcome.Done(JsonNull)
    }

    private fun f(o: JsonObject, k: String): String? = o[k]?.let { it.stringOrNull ?: (it as? JsonPrimitive)?.content }

    /** `match` is an argv of globs (`*` = any run of characters); absent matches anything. */
    private fun matches(pattern: JsonArray?, argv: List<String>): Boolean {
        val globs = pattern?.mapNotNull { it.stringOrNull } ?: return true
        return globs.size == argv.size && globs.zip(argv).all { (g, a) -> Regex(g.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.DOT_MATCHES_ALL).matches(a) }
    }

    private fun languageOf(path: String): String = when (path.substringAfterLast('.', "")) {
        "py" -> "python"; "ts" -> "typescript"; "js" -> "javascript"; "go" -> "go"; "rs" -> "rust"; "md" -> "markdown"
        "sh" -> "shellscript"; "c" -> "c"; "cc", "cpp", "hpp" -> "cpp"; "json" -> "json"; "yaml", "yml" -> "yaml"
        else -> "plaintext"
    }
}
