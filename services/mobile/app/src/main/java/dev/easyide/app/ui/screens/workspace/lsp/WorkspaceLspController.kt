package dev.easyide.app.ui.screens.workspace.lsp

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import dev.easyide.app.data.settings.LspSettingsSchema
import dev.easyide.app.lsp.DiskEditPort
import dev.easyide.app.lsp.LspRuntime
import dev.easyide.app.lsp.ServerQuestion
import dev.easyide.app.lsp.servers.InstallRecipe
import dev.easyide.app.ui.screens.workspace.EditorInteraction
import dev.easyide.app.ui.screens.workspace.EditorTab
import dev.easyide.app.ui.screens.workspace.SelectionRequest
import dev.easyide.app.ui.screens.workspace.decor.DecorationRegistry
import dev.easyide.app.ui.screens.workspace.edit.TextState
import dev.easyide.lsp.manager.ServerStatus
import dev.easyide.lsp.protocol.LspFeature
import dev.easyide.lsp.session.ServerKey
import dev.easyide.lsp.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One server of the active document's language, as the status item and its menu show it. */
data class ServerStatusUi(
    val key: ServerKey,
    val kind: ServerStatusKind,
    val command: String,
    val install: InstallRecipe?,
    val rssKb: Long?,
    val detail: String?,
    val stderrTail: List<String>,
)

/** The side panel tabs of the right stage. */
enum class LspPanel { PROBLEMS, REFERENCES, OUTLINE }

/**
 * The LSP integration of one workspace (lsp-features.md sec 8 "LspWorkspaceBridge"): keeps the
 * servers' documents in step with the editor tabs, owns every feature presenter, and is the
 * editor's [EditorInteraction]. Built by the WorkspaceViewModel and released with it; the
 * servers themselves are app-wide and go idle when the last document closes.
 */
class WorkspaceLspController(
    private val runtime: LspRuntime,
    host: LspWorkspaceHost,
    decorations: DecorationRegistry,
    private val scope: CoroutineScope,
    private val environmentId: String,
    private val projectId: String,
    projectRoot: File,
    rootfsDir: File,
) : EditorInteraction {

    private val documents = LspDocuments(runtime.client, runtime.manager.documentStore(environmentId, projectId), environmentId, projectId, projectRoot)

    private val ws = LspWorkspace(
        client = runtime.client,
        manager = runtime.manager,
        settingsState = runtime.projectSettings(environmentId, projectId),
        documents = documents,
        host = host,
        decorations = decorations,
        scope = scope,
        environmentId = environmentId,
        projectId = projectId,
        rootfsLabel = { file -> file.relativeToOrNull(rootfsDir)?.invariantSeparatorsPath?.let { "/$it" } },
    )

    val snippets = SnippetController(ws)
    val diagnostics = DiagnosticsPresenter(ws)
    val completion = CompletionController(ws, snippets)
    val info = InfoController(ws)
    val navigation = NavigationController(ws)
    val actions = EditActionsController(ws, diagnostics)
    private val caretDecorations = CaretDecorations(ws)

    /** Extension `lspRequest`s against this workspace's servers. */
    val extensionRequests = LspRequestGateway(ws, navigation) { showPanel(LspPanel.REFERENCES) }

    private val panelState = MutableStateFlow<LspPanel?>(null)
    private val dismissedInstall = MutableStateFlow<Set<ServerKey>>(emptySet())
    private val editPortRegistration = runtime.editPorts.register(environmentId, projectId, ws.port(DiskEditPort(Dispatchers.IO)))

    /** Which right-stage panel is showing, or null when the stage is closed. */
    val panel: StateFlow<LspPanel?> = panelState.asStateFlow()

    override val selectionRequests: StateFlow<SelectionRequest?> get() = ws.selectionRequests

    /** Server questions (`showMessageRequest`) of this project, oldest first. */
    val questions: StateFlow<List<ServerQuestion>> = runtime.messages.questions
        .map { list -> list.filter { it.key.environmentId == environmentId && it.key.projectId == projectId } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The servers of the active document's language. */
    val statuses: StateFlow<List<ServerStatusUi>> = combine(
        runtime.manager.statuses,
        runtime.servers.resolved(environmentId, projectId),
        host.state.map { it.activeTabPath }.distinctUntilChanged(),
        documents.paths,
    ) { statuses, resolved, active, _ ->
        val lang = active?.let(documents::doc)?.languageId ?: return@combine emptyList()
        resolved.servers.filter { lang in it.config.languages }.mapNotNull { r ->
            val key = ServerKey(environmentId, projectId, r.config.serverId)
            statuses[key]?.let { statusUi(it, r.config.command.first(), r.install) }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Per language of this project's servers: the facts behind the `lsp*` context keys. */
    val languageFacts: StateFlow<Map<String, LspLanguageFacts>> = runtime.manager.statuses
        .map { m ->
            val mine = m.values.filter { it.key.environmentId == environmentId && it.key.projectId == projectId }
            LspLanguageFacts.of(mine) { key, feature -> runtime.manager.session(key)?.supports(feature) == true }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** The first not-installed server of the active language the user has not dismissed. */
    val installNotice: StateFlow<ServerStatusUi?> = combine(statuses, dismissedInstall) { list, dismissed ->
        list.firstOrNull { (it.kind == ServerStatusKind.NOT_INSTALLED || it.kind == ServerStatusKind.ENVIRONMENT_NOT_READY) && it.key !in dismissed }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private var modifiersHeld = false

    init {
        diagnostics.start()
        scope.launch {
            // Settings too: `lsp.enabled` (per language) opens or closes documents.
            combine(host.state.map { it.openTabs }.distinctUntilChanged(), ws.settingsState) { tabs, settings -> tabs to settings }.conflate().collect { (tabs, settings) ->
                withContext(Dispatchers.Default) { documents.sync(tabs) { lang -> settings.get(LspSettingsSchema.enabled, lang) } }
                ws.onTabsChanged(host.state.value)
            }
        }
        scope.launch {
            combine(host.state.map { it.activeTabPath }.distinctUntilChanged(), documents.paths) { active, _ -> active }.collect { active ->
                val uri = active?.let(documents::doc)?.uri
                runtime.manager.onVisibleUrisChanged(environmentId, projectId, setOfNotNull(uri), uri)
                completion.close()
                info.dismissHover()
                info.dismissSignature()
                actions.closeMenu()
                if (panelState.value == LspPanel.OUTLINE) navigation.refreshOutline()
            }
        }
        scope.launch {
            // A server that just became ready answers what was asked while it was starting.
            runtime.manager.statuses
                .map { m -> m.filterKeys { it.environmentId == environmentId && it.projectId == projectId }.count { it.value.state is SessionState.Running } }
                .distinctUntilChanged()
                .filter { it > 0 }
                .collect {
                    ws.activeTab()?.relativePath?.let(caretDecorations::refreshInlays)
                    if (panelState.value == LspPanel.OUTLINE) navigation.refreshOutline()
                }
        }
        scope.launch {
            runtime.messages.messages
                .filter { it.key.environmentId == environmentId && it.key.projectId == projectId }
                .collect { host.showStatus("${it.key.serverId}: ${it.text}") }
        }
    }

    /** Ends the integration: documents close (servers go idle), buffer edits route to disk. */
    fun release() {
        editPortRegistration.close()
        documents.closeAll()
        runtime.client.releaseProject(environmentId, projectId)
    }

    // ---- save ---------------------------------------------------------------------------

    /** Save participants; returns the text to write (formatted, fixed), never throws. */
    suspend fun beforeSave(tab: EditorTab): String = if (documents.doc(tab.relativePath) == null) tab.content else actions.beforeSave(tab)

    fun afterSave(path: String, text: String) {
        documents.saved(path, text)
        if (panelState.value == LspPanel.OUTLINE) navigation.refreshOutline()
    }

    // ---- panels, status and install -----------------------------------------------------------

    fun showPanel(panel: LspPanel?) {
        panelState.value = panel
        if (panel == LspPanel.OUTLINE) navigation.refreshOutline()
    }

    fun togglePanel(panel: LspPanel) = showPanel(if (panelState.value == panel) null else panel)

    fun restart(key: ServerKey) = runtime.manager.restart(key)

    fun stop(key: ServerKey) = runtime.manager.stop(key)

    fun start(key: ServerKey) = runtime.manager.start(key)

    /** The session's log ring (stderr, `logMessage`, transitions, trace). */
    fun logOf(key: ServerKey): List<String> = runtime.manager.session(key)?.log?.snapshot().orEmpty()

    /** Runs [recipe] in a visible terminal; "Retry" re-probes once the user saw it finish. */
    fun install(status: ServerStatusUi) {
        val recipe = status.install ?: return
        ws.host.runInTerminal(recipe.script())
    }

    fun retryProbe(key: ServerKey) = runtime.manager.retryProbe(key)

    fun dismissInstall(key: ServerKey) {
        dismissedInstall.value = dismissedInstall.value + key
    }

    /** `lspSupports:<lang>:<feature>` for the active document: command enablement. */
    fun supports(feature: LspFeature): Boolean {
        val doc = ws.activeTab()?.relativePath?.let(documents::doc) ?: return false
        return runtime.manager.sessionsFor(environmentId, projectId, doc.languageId).any { it.supports(feature) }
    }

    /** Whether any open document's servers offer [feature] (workspace-wide commands). */
    fun supportsAnyOpen(feature: LspFeature): Boolean = documents.open.map { it.languageId }.distinct().any { lang ->
        runtime.manager.sessionsFor(environmentId, projectId, lang).any { it.supports(feature) }
    }

    fun showHoverAtCaret() {
        ws.activeCaret()?.let { info.showHover(it.path, it.offset, HoverOrigin.KEYBOARD) }
    }

    fun navigate(location: NavLocation) = ws.navigate(location)

    fun openLocations(kind: NavKind) {
        navigation.goTo(kind)
        if (kind == NavKind.REFERENCES) showPanel(LspPanel.REFERENCES)
    }

    // ---- EditorInteraction ----------------------------------------------------------------

    private var typed: Char? = null

    override fun onPreviewKey(path: String, event: KeyEvent): Boolean {
        val held = event.isCtrlPressed && event.isAltPressed
        if (held != modifiersHeld) {
            modifiersHeld = held
            caretDecorations.onModifiers(held)
        }
        if (event.type != KeyEventType.KeyDown) return false
        if (completion.isOpen) {
            when (event.key) {
                Key.DirectionDown -> return true.also { completion.moveSelection(1) }
                Key.DirectionUp -> return true.also { completion.moveSelection(-1) }
                Key.PageDown -> return true.also { completion.moveSelection(LspUiPolicy.COMPLETION_PAGE_ROWS) }
                Key.PageUp -> return true.also { completion.moveSelection(-LspUiPolicy.COMPLETION_PAGE_ROWS) }
                Key.Escape -> return true.also { completion.close() }
                Key.Tab -> return true.also { completion.ui.value?.let { completion.acceptAsync(it.selected, AcceptMode.REPLACE) } }
                else -> Unit
            }
        }
        val active = snippets.active.value
        if (active != null && active.path == path) {
            when (event.key) {
                Key.Tab -> return snippets.move(forward = !event.isShiftPressed)
                Key.Escape -> return true.also { snippets.end() }
                else -> Unit
            }
        }
        return false
    }

    override fun transformEdit(path: String, before: TextState, after: TextState): TextState? {
        typed = CompletionModel.typedChar(before, after)
        return completion.transformEdit(path, before, after)
    }

    override fun onCaretChanged(path: String, text: String, selection: TextRange) {
        val previous = ws.caret.value
        val textChanged = previous == null || previous.path != path || (previous.text !== text && previous.text != text)
        val caret = Caret(path, text, selection)
        ws.updateCaret(caret)
        if (documents.doc(path) == null) return
        val char = typed.takeIf { textChanged }
        typed = null
        if (textChanged) snippets.onText(path, text)
        completion.onCaret(caret)
        info.onCaret(caret, char, textChanged)
        actions.onCaret(caret)
        caretDecorations.onCaret(caret, textChanged)
        if (char != null) actions.onTyped(caret, char)
    }

    override fun onVisibleLinesChanged(path: String, first: Int, last: Int) = caretDecorations.onVisibleLines(path, first, last)

    override fun onLongPress(path: String, offset: Int) = info.showHover(path, offset, HoverOrigin.LONG_PRESS)

    override fun onPointerHover(path: String, offset: Int?) = info.onPointerHover(path, offset)

    override fun onCtrlClick(path: String, offset: Int) = navigation.goTo(NavKind.DEFINITION, path, offset)

    override fun onSelectionRequestApplied(request: SelectionRequest) = ws.onSelectionApplied(request)

    fun onGutterTap(path: String, line: Int) = actions.onGutterTap(path, line)

    private fun statusUi(s: ServerStatus, command: String, install: InstallRecipe?) = ServerStatusUi(
        key = s.key,
        kind = ServerStatusKind.of(s),
        command = command,
        install = install,
        rssKb = s.rssKb,
        detail = s.failureDetail(),
        stderrTail = s.stderrTail(),
    )
}
