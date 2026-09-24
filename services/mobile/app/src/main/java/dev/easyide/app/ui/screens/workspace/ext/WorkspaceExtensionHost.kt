package dev.easyide.app.ui.screens.workspace.ext

import androidx.compose.ui.text.TextRange
import dev.easyide.app.extensions.ExtensionUiPolicy
import dev.easyide.app.extensions.ExtensionsContainer
import dev.easyide.app.extensions.adapters.EditorEdit
import dev.easyide.app.extensions.adapters.EditorKeys
import dev.easyide.app.extensions.adapters.EditorText
import dev.easyide.app.ui.commands.KeyChord
import dev.easyide.app.ui.commands.KeyNames
import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.app.extensions.adapters.SyncVariables
import dev.easyide.app.extensions.adapters.TaskSpec
import dev.easyide.app.extensions.adapters.Tasks
import dev.easyide.app.extensions.host.ActiveDocument
import dev.easyide.app.extensions.host.TextEdits
import dev.easyide.app.extensions.host.WorkspaceBridge
import dev.easyide.app.ui.commands.CommandRegistry
import dev.easyide.app.ui.screens.workspace.EditorSelections
import dev.easyide.app.ui.screens.workspace.GitPanelState
import dev.easyide.app.ui.screens.workspace.WorkspaceTerminals
import dev.easyide.app.ui.screens.workspace.WorkspaceUiState
import dev.easyide.app.ui.screens.workspace.edit.LineCommentToggle
import dev.easyide.app.ui.screens.workspace.lsp.LspLanguageFacts
import dev.easyide.app.ui.screens.workspace.lsp.LspRequestGateway
import dev.easyide.extensions.action.ActionOutcome
import dev.easyide.extensions.action.CommandOutcome
import dev.easyide.extensions.action.EditorState
import dev.easyide.extensions.action.ExecOutcome
import dev.easyide.extensions.action.GuestPaths
import dev.easyide.extensions.action.LspOutcome
import dev.easyide.extensions.action.LspThen
import dev.easyide.extensions.action.ResolvedTask
import dev.easyide.extensions.action.ResolvedTextEdit
import dev.easyide.extensions.action.TaskOutcome
import dev.easyide.extensions.action.TerminalRequest
import dev.easyide.extensions.action.WorkspaceState
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.contrib.StagePlacement
import dev.easyide.extensions.settings.ExtensionSettings
import dev.easyide.extensions.settings.ExtensionSettings.int
import dev.easyide.extensions.settings.RuntimeScope
import dev.easyide.extensions.settings.SettingsQuery
import dev.easyide.sandbox.EnvironmentManager
import dev.easyide.sandbox.LinuxEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import dev.easyide.app.extensions.host.UiPrompt
import dev.easyide.extensions.action.PickItem
import dev.easyide.extensions.action.QuickPickRequest
import dev.easyide.extensions.json.intOrNull
import java.io.File
import java.io.IOException

/** Texts of a built-in picker (resolved from resources by the screen). */
data class PickerLabels(val title: String, val placeHolder: String, val empty: String)

/** A stage the host asked the screen to show; [focus] also moves focus there. */
data class StageRequest(val stage: StagePlacement, val focus: Boolean)

/**
 * One open workspace as the extension host sees it ([WorkspaceBridge]): its terminals,
 * buffers, stages, built-in commands and tasks, with guest paths translated to project
 * paths. Lives as long as the workspace's ViewModel; [attach] makes it the host's target
 * and sets the runtime scope (environment-scoped packs follow), [detach] undoes both.
 *
 * UI state (tabs, carets, terminals) is only touched on the main thread; file and
 * process work runs on IO.
 */
class WorkspaceExtensionHost(
    private val projectId: String,
    override val environmentId: String,
    private val projectRoot: File,
    private val state: StateFlow<WorkspaceUiState>,
    git: StateFlow<GitPanelState>,
    private val selections: EditorSelections,
    private val terminals: WorkspaceTerminals,
    private val editor: EditorBuffers,
    private val extensions: ExtensionsContainer,
    private val lsp: LspRequestGateway,
    lspFacts: StateFlow<Map<String, LspLanguageFacts>>,
    environmentManager: EnvironmentManager,
    linuxEnvironment: LinuxEnvironment,
    private val scope: CoroutineScope,
) : WorkspaceBridge {

    private val linux = linuxEnvironment
    private val runtime = extensions.runtime
    val context = WorkspaceContextFeed(
        environmentId, projectRoot, state, git, selections, runtime, environmentManager, linuxEnvironment, lspFacts, scope,
    )

    /** Tab and caret changes for WASM extensions (`workspace.*`, `editor.didChangeSelection`). */
    private val wasmEvents = WasmEventFeed(state, selections, context::languageOf, ::selectionJson, extensions.wasm::post, scope)

    /** The built-in `editor.action.commentLine` over this workspace's buffers. */
    val lineComments = LineCommentToggle(state, selections, editor, scope)

    /** Set by the screen each composition: the name shown for `${workspaceFolderBasename}`. */
    @Volatile var projectName: String = ""

    /** The workspace's current command list (rebuilt per composition), for `executeCommand` of built-ins. */
    @Volatile var commands: CommandRegistry = CommandRegistry(emptyList())

    private val stages = MutableSharedFlow<StageRequest>(extraBufferCapacity = STAGE_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val stageRequests: SharedFlow<StageRequest> = stages.asSharedFlow()

    fun attach() {
        extensions.host.attach(this)
        extensions.setRuntimeScope(RuntimeScope(environmentId, projectId))
        context.start()
        wasmEvents.start()
    }

    fun detach() {
        extensions.host.detach(this)
        extensions.setRuntimeScope(RuntimeScope.NONE)
        context.clear()
        wasmEvents.stop()
    }

    /**
     * Runs an extension (or built-in) command the way the user invoked it. A failure shows
     * a snackbar with the command title (the runner has already logged the details); a
     * dismissed prompt is silent. Runs in the workspace scope, so closing the workspace
     * cancels it.
     */
    fun run(commandId: String, args: JsonElement? = null) {
        scope.launch(Dispatchers.Main.immediate) {
            val outcome = runtime.run(commandId, args)
            if (outcome is ActionOutcome.Failed) {
                val title = runtime.contributions.snapshot.value.commands.firstOrNull { it.value.command == commandId }?.value?.title ?: commandId
                extensions.ui.notify("$title: ${outcome.message}")
            }
        }
    }

    /** The "Insert Snippet" command: a quick pick of the active language's snippets. */
    fun pickSnippet(labels: PickerLabels) {
        scope.launch(Dispatchers.Main.immediate) {
            val tab = state.value.activeTab?.takeIf { it.editable }
            val list = tab?.let { extensions.snippets.value.forLanguage(context.languageOf(it.relativePath)) }.orEmpty()
            if (list.isEmpty()) { extensions.ui.notify(labels.empty); return@launch }
            val items = list.mapIndexed { i, sn ->
                PickItem(sn.name, listOfNotNull(sn.prefixes.firstOrNull(), sn.description).joinToString(" - ").ifEmpty { null }, JsonPrimitive(i))
            }
            val index = pick(labels, items) ?: return@launch
            insertSnippet(list[index].body, null, null)
        }
    }

    /** The "Run Task" command: a quick pick of `.easyide/tasks.json`, run in a command terminal. */
    fun pickTask(labels: PickerLabels, exitedWith: (String, Int) -> String) {
        scope.launch(Dispatchers.Main.immediate) {
            val list = tasks()
            if (list.isEmpty()) { extensions.ui.notify(labels.empty); return@launch }
            val index = pick(labels, list.mapIndexed { i, t -> PickItem(t.label, t.type, JsonPrimitive(i)) }) ?: return@launch
            val spec = list[index]
            when (val r = runTaskSpec(spec)) {
                is ExecOutcome.Exited -> if (r.exitCode != 0) extensions.ui.notify(exitedWith(spec.label, r.exitCode))
                is ExecOutcome.TimedOut -> extensions.ui.notify(exitedWith(spec.label, -1))
                is ExecOutcome.Unavailable -> extensions.ui.notify(r.reason)
            }
        }
    }

    /**
     * A key-row key tapped on the editor surface: `insert` types its text, `snippet` runs
     * the snippet engine, `command` runs the command, `key` edits or moves the caret; a
     * `key` chord that is no editing key is a shortcut, handed to [onShortcut] (the keymap).
     */
    fun editorKey(action: KeyAction, onShortcut: (KeyChord) -> Unit) {
        when (action) {
            is KeyAction.Command -> run(action.id)
            is KeyAction.Snippet -> scope.launch(Dispatchers.Main.immediate) { insertSnippet(action.body, null, null) }
            is KeyAction.Insert -> editActive { text, a, b ->
                EditorEdit(text.substring(0, a) + action.text + text.substring(b), a + action.text.length, a + action.text.length)
            }
            is KeyAction.Key -> {
                val chord = KeyNames.parse(action.chord) ?: return
                val plain = !chord.ctrl && !chord.alt && !chord.meta
                if (!plain || !editActive { text, a, b -> EditorKeys.apply(text, a, b, chord.keyCode) }) onShortcut(chord)
            }
        }
    }

    /** Applies [edit] to the active editable tab at its selection; false when there is none or [edit] declines. */
    private fun editActive(edit: (String, Int, Int) -> EditorEdit?): Boolean {
        val tab = state.value.activeTab?.takeIf { it.editable } ?: return false
        val sel = selections[tab.relativePath]
        val result = edit(tab.content, sel.min.coerceIn(0, tab.content.length), sel.max.coerceIn(0, tab.content.length)) ?: return false
        editor.replaceContent(tab.relativePath, result.text)
        selections[tab.relativePath] = TextRange(result.selectionStart, result.selectionEnd)
        return true
    }

    private suspend fun pick(labels: PickerLabels, items: List<PickItem>): Int? =
        extensions.ui.show(UiPrompt.QuickPick(QuickPickRequest(labels.title, items, labels.placeHolder, canPickMany = false)))
            ?.firstOrNull()?.intOrNull

    // ---- WorkspacePort pieces

    override fun workspaceState(): WorkspaceState =
        WorkspaceState(WorkspaceState.GUEST_WORKSPACE, projectName, environmentId, context.environmentName)

    override fun editorState(): EditorState? {
        val tab = state.value.activeTab ?: return null
        val sel = selections[tab.relativePath]
        val text = tab.content
        val caret = sel.end.coerceIn(0, text.length)
        val (line, column) = TextEdits.lineColumn(text, caret)
        return EditorState(
            path = guestPath(tab.relativePath),
            languageId = context.languageOf(tab.relativePath),
            line = line,
            column = column,
            selectedText = text.substring(sel.min.coerceIn(0, text.length), sel.max.coerceIn(0, text.length)),
            currentWord = EditorText.wordAt(text, caret),
            lineText = EditorText.lineAt(text, caret),
        )
    }

    // ---- WASM editor access

    override val projectDirectory: File get() = projectRoot

    override fun activeDocument(): ActiveDocument? {
        val tab = state.value.activeTab ?: return null
        val sel = selections[tab.relativePath]
        val path = guestPath(tab.relativePath)
        return ActiveDocument(
            path, context.languageOf(tab.relativePath), wasmEvents.diff.version(path), tab.content,
            sel.min.coerceIn(0, tab.content.length), sel.max.coerceIn(0, tab.content.length), tab.editable,
        )
    }

    override suspend fun select(path: String, start: Int, end: Int): Boolean = withContext(Dispatchers.Main.immediate) {
        val rel = relative(path) ?: return@withContext false
        val tab = state.value.openTabs.find { it.relativePath == rel } ?: return@withContext false
        selections[rel] = TextRange(start.coerceIn(0, tab.content.length), end.coerceIn(0, tab.content.length))
        true
    }

    /** `{start, end}` LSP positions of a selection, for `editor.didChangeSelection`. */
    private fun selectionJson(text: String, start: Int, end: Int): kotlinx.serialization.json.JsonObject {
        fun pos(offset: Int): kotlinx.serialization.json.JsonObject {
            val (line, column) = TextEdits.lineColumn(text, offset)
            return kotlinx.serialization.json.buildJsonObject {
                put("line", JsonPrimitive(line - 1))
                put("character", JsonPrimitive(column - 1))
            }
        }
        return kotlinx.serialization.json.buildJsonObject { put("start", pos(start)); put("end", pos(end)) }
    }

    // ---- terminals and processes

    override suspend fun runInTerminal(request: TerminalRequest) = withContext(Dispatchers.Main.immediate) {
        val tab = terminals.namedTerminal(request.owner, request.terminalName) ?: return@withContext
        if (request.clear) tab.session.write(CLEAR_SCREEN)
        tab.session.write(request.commandLine + ENTER)
        if (request.focus) {
            terminals.select(tab.id)
            stages.tryEmit(StageRequest(StagePlacement.BOTTOM, focus = true))
        }
    }

    override suspend fun runCommandTerminal(title: String, argv: List<String>, cwd: String?, env: Map<String, String>, timeoutMs: Long): ExecOutcome =
        withContext(Dispatchers.Main.immediate) {
            stages.tryEmit(StageRequest(StagePlacement.BOTTOM, focus = false))
            terminals.runCommand(title, argv, cwd, env, timeoutMs)
        }

    override suspend fun startCaptured(argv: List<String>, cwd: String?, env: Map<String, String>): Process =
        linux.startPiped(environmentId, projectRoot, argv, env, cwd)

    // ---- editor

    override suspend fun openFile(path: String, line: Int?, column: Int?): Boolean {
        val rel = relative(path) ?: return false
        if (!withContext(Dispatchers.IO) { File(projectRoot, rel).isFile }) return false
        return withContext(Dispatchers.Main.immediate) {
            if (!editor.openAndAwait(rel)) return@withContext false
            val text = state.value.openTabs.find { it.relativePath == rel }?.content ?: return@withContext true
            if (line != null) {
                val starts = TextEdits.lineStarts(text)
                val pos = dev.easyide.extensions.action.TextPosition((line - 1).coerceAtLeast(0), ((column ?: 1) - 1).coerceAtLeast(0))
                TextEdits.offsetOf(text, starts, pos)?.let { selections[rel] = TextRange(it) }
            }
            true
        }
    }

    override suspend fun applyEdits(edits: List<ResolvedTextEdit>): Boolean = withContext(Dispatchers.Main.immediate) {
        // All or nothing: every file's new text is computed before anything is written.
        val planned = edits.groupBy { it.path }.map { (path, fileEdits) ->
            val rel = relative(path) ?: return@withContext false
            val open = state.value.openTabs.find { it.relativePath == rel }
            val before = open?.takeIf { it.editable }?.content ?: if (open == null) editor.readClosedFile(rel) else null
            val after = before?.let { TextEdits.apply(it, fileEdits.map { e -> e.range to e.text }) } ?: return@withContext false
            Triple(rel, open != null, after)
        }
        planned.all { (rel, isOpen, after) -> if (isOpen) editor.replaceContent(rel, after) else editor.writeClosedFile(rel, after) }
    }

    override suspend fun insertSnippet(body: String?, name: String?, language: String?): Boolean {
        val tab = state.value.activeTab?.takeIf { it.editable } ?: return false
        val lang = language ?: context.languageOf(tab.relativePath)
        val source = body ?: name?.let { extensions.snippets.value.named(it, lang)?.body } ?: return false
        val comments = withContext(Dispatchers.IO) { dev.easyide.app.ui.screens.workspace.syntax.LanguageConfigs.forFile(tab.name) }
        return withContext(Dispatchers.Main.immediate) {
            val current = state.value.openTabs.find { it.relativePath == tab.relativePath } ?: return@withContext false
            val sel = selections[current.relativePath]
            val text = current.content
            val from = sel.min.coerceIn(0, text.length)
            val to = sel.max.coerceIn(0, text.length)
            val vars = SnippetVariables(current.name, guestPath(current.relativePath), projectName, text, from, to, comments)
            val expanded = dev.easyide.app.extensions.adapters.SnippetBody.expand(source, EditorText.indentAt(text, from), vars::resolve)
            val next = text.substring(0, from) + expanded.text + text.substring(to)
            if (!editor.replaceContent(current.relativePath, next)) return@withContext false
            selections[current.relativePath] = TextRange(from + expanded.selectionStart, from + expanded.selectionEnd)
            true
        }
    }

    // ---- stages and commands

    override fun revealStage(stage: String, focus: Boolean): Boolean {
        val placement = StagePlacement.parse(stage) ?: return false
        return stages.tryEmit(StageRequest(placement, focus))
    }

    override suspend fun executeBuiltIn(commandId: String, args: JsonElement?): CommandOutcome = withContext(Dispatchers.Main.immediate) {
        val command = commands[commandId] ?: return@withContext CommandOutcome.NotFound
        if (!command.enabled) return@withContext CommandOutcome.Failed("$commandId is not available right now")
        command.invoke(args)
        CommandOutcome.Done(JsonNull)
    }

    // ---- language servers

    override suspend fun lspRequest(language: String, method: String, params: JsonElement?, then: LspThen): LspOutcome =
        lsp.request(language, method, params, then)

    // ---- tasks

    /** Tasks of `.easyide/tasks.json`; empty when the file is missing, oversized or unreadable. */
    suspend fun tasks(): List<TaskSpec> = withContext(Dispatchers.IO) {
        val file = File(projectRoot, Tasks.FILE)
        try {
            if (!file.isFile || file.length() > ExtensionUiPolicy.TASKS_FILE_MAX_BYTES) emptyList() else Tasks.parse(file.readText()).orEmpty()
        } catch (e: IOException) {
            emptyList()
        }
    }

    override suspend fun runTask(task: ResolvedTask): TaskOutcome {
        val all = tasks()
        val spec = when (task) {
            is ResolvedTask.Label -> all.firstOrNull { it.label == task.label }
            is ResolvedTask.Definition -> Tasks.matching(all, task.definition)
        } ?: return TaskOutcome.NotFound
        return when (val r = runTaskSpec(spec)) {
            is ExecOutcome.Exited -> TaskOutcome.Exited(r.exitCode)
            is ExecOutcome.TimedOut -> TaskOutcome.Unavailable("task '${spec.label}' timed out")
            is ExecOutcome.Unavailable -> TaskOutcome.Unavailable(r.reason)
        }
    }

    /** Runs [spec] in a command terminal, variables in its fields substituted. */
    suspend fun runTaskSpec(spec: TaskSpec): ExecOutcome {
        val ws = workspaceState()
        val ed = editorState()
        val config = { key: String -> extensions.settings.value(key, SettingsQuery(ed?.languageId, environmentId, projectId)) }
        val expand = { text: String -> SyncVariables.render(text, config, ed, ws, Owner.BuiltIn) }
        val timeoutMs = extensions.settings.int(ExtensionSettings.ACTIONS_EXEC_TIMEOUT_SEC) * MS_PER_SEC
        return runCommandTerminal(spec.label, spec.argv(expand), spec.cwd?.let(expand), spec.env.mapValues { expand(it.value) }, timeoutMs)
    }

    private fun guestPath(relativePath: String) = WorkspaceState.GUEST_WORKSPACE + "/" + relativePath

    private fun relative(guestPath: String): String? =
        GuestPaths.relativeTo(WorkspaceState.GUEST_WORKSPACE, guestPath)?.takeIf { it.isNotEmpty() }

    private companion object {
        const val ENTER = "\r"

        /** Ctrl-L: readline's clear-screen, what `clear: true` means at a shell prompt. */
        const val CLEAR_SCREEN = "\u000C"
        const val STAGE_BUFFER = 4
        const val MS_PER_SEC = 1000L
    }
}
