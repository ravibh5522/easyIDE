package dev.easyide.app.ui.screens.workspace

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.easyide.app.diagnostics.LogLevel
import dev.easyide.app.diagnostics.LogSink
import dev.easyide.app.diagnostics.LogSource
import dev.easyide.app.extensions.ExtensionsContainer
import dev.easyide.app.data.UiPreferences
import dev.easyide.app.lsp.LspRuntime
import dev.easyide.app.session.ExternalState
import dev.easyide.app.session.SessionStore
import dev.easyide.app.ui.screens.workspace.decor.DecorationRegistry
import dev.easyide.app.ui.screens.workspace.ext.EditorBuffers
import dev.easyide.app.ui.screens.workspace.ext.WorkspaceExtensionHost
import dev.easyide.app.ui.screens.workspace.lsp.LspWorkspaceHost
import dev.easyide.app.ui.screens.workspace.lsp.WorkspaceLspController
import dev.easyide.app.ui.screens.workspace.session.WorkspaceSession
import dev.easyide.app.ui.screens.workspace.session.WorkspaceSessionHost
import dev.easyide.app.ui.screens.workspace.session.WorkspaceSessionUi
import dev.easyide.sandbox.EnvironmentManager
import dev.easyide.sandbox.LinuxEnvironment
import dev.easyide.sandbox.ProjectManager
import dev.easyide.sandbox.files.FileContent
import dev.easyide.sandbox.files.FileNode
import dev.easyide.sandbox.files.FilePolicy
import dev.easyide.sandbox.git.GitCredentials
import dev.easyide.sandbox.git.GitRemote
import dev.easyide.sandbox.git.GitService
import dev.easyide.app.data.settings.SettingsStore
import dev.easyide.app.ui.screens.workspace.git.StoreGitSettings
import dev.easyide.app.ui.screens.workspace.git.asRunner
import dev.easyide.app.ui.screens.workspace.git.asSink
import dev.easyide.sandbox.files.ProjectFileWatcher
import dev.easyide.sandbox.files.ProjectFiles
import dev.easyide.app.ui.screens.workspace.syntax.TextMateHighlighter
import dev.easyide.sandbox.model.SandboxImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Drives the workspace: the file tree, open editor buffers, and the terminals.
 * All of it operates on real files in the project directory, through the
 * sandbox when one is provisioned.
 */
class WorkspaceViewModel(
    private val projectId: String,
    private val environmentId: String,
    private val projectFiles: ProjectFiles,
    private val linuxEnvironment: LinuxEnvironment,
    private val environmentManager: EnvironmentManager,
    private val projectManager: ProjectManager,
    private val appContext: Context,
    private val imageProvider: suspend (String) -> SandboxImage,
    private val gitService: GitService,
    gitRemote: GitRemote,
    gitCredentials: GitCredentials,
    settingsStore: SettingsStore,
    appForeground: StateFlow<Boolean>,
    lspRuntime: LspRuntime,
    extensions: ExtensionsContainer,
    sessionStore: SessionStore,
    restoreOpenTabs: suspend () -> Boolean,
    /** The previous session of this project, if it is still writing its last save; restore waits for it. */
    settled: Job?,
    private val log: LogSink,
    uiPreferences: UiPreferences,
) : ViewModel(), EditorBuffers {

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    private val git = WorkspaceGitController(
        gitService = gitService,
        projectRoot = projectFiles.projectRoot(projectId),
        scope = viewModelScope,
        network = gitRemote.asRunner(environmentId),
        settings = StoreGitSettings(settingsStore, environmentId, projectId),
        tokens = gitCredentials.asSink(),
        foreground = appForeground,
    )
    val gitState: StateFlow<GitPanelState> = git.state

    /** Per-document editor decorations; producers (LSP, find) write, `EditorPane` paints. */
    val decorations = DecorationRegistry()

    /** Caret/selection per open tab, shared by the editor and extension actions. */
    val selections = EditorSelections()

    /** Undo/redo, find and replace, quick open: the editing workflows over the state above. */
    val editing: WorkspaceEditing = WorkspaceEditing(
        scope = viewModelScope,
        state = uiState,
        selections = selections,
        decorations = decorations,
        setContent = ::onContentChanged,
        projectId = projectId,
        projectFiles = projectFiles,
        preferences = uiPreferences,
    )

    val terminals = WorkspaceTerminals(
        _uiState, viewModelScope, appContext, linuxEnvironment, environmentId, projectFiles.projectRoot(projectId),
    ) { message ->
        log.log(LogLevel.WARN, LogSource.SANDBOX, "terminal: $message")
        setStatus(message)
    }

    /** Scroll, layout, hot-exit persistence and restore, external file changes: everything that outlives a screen or the process. */
    internal val session = WorkspaceSession(
        projectId = projectId,
        state = _uiState,
        selections = selections,
        scope = viewModelScope,
        appContext = appContext,
        host = object : WorkspaceSessionHost {
            override fun refreshTree() = this@WorkspaceViewModel.refreshTree()
            override fun syncFileWatcher() = this@WorkspaceViewModel.syncFileWatcher(_uiState.value.expandedDirs)
            override fun showStatus(message: String) = setStatus(message)
        },
        projectFiles = projectFiles,
        store = sessionStore,
        io = Dispatchers.IO,
        clock = System::currentTimeMillis,
        log = log,
        restoreOpenTabs = restoreOpenTabs,
    )

    val sessionUi: WorkspaceSessionUi = session.ui

    /** Language servers for this workspace's tabs: documents, decorations, popups, panels. */
    val lsp = WorkspaceLspController(
        runtime = lspRuntime,
        host = object : LspWorkspaceHost {
            override val state: StateFlow<WorkspaceUiState> get() = uiState
            override fun setContent(path: String, text: String) = onContentChanged(path, text)
            override fun openProjectFile(path: String) = openFileByPath(path)
            override fun openTab(tab: EditorTab) = openVirtualTab(tab)
            override fun showStatus(message: String) = setStatus(message)
            override fun runInTerminal(command: String) = terminals.newShell(initialCommand = command)
            override fun string(id: Int, vararg args: Any): String = appContext.getString(id, *args)
        },
        decorations = decorations,
        scope = viewModelScope,
        environmentId = environmentId,
        projectId = projectId,
        projectRoot = projectFiles.projectRoot(projectId).canonicalFile,
        rootfsDir = lspRuntime.rootfsDir(environmentId),
    )

    /** This workspace as the extension host sees it; attached while the workspace lives. */
    val extensionHost = WorkspaceExtensionHost(
        projectId = projectId,
        environmentId = environmentId,
        projectRoot = projectFiles.projectRoot(projectId),
        state = uiState,
        git = git.state,
        selections = selections,
        terminals = terminals,
        editor = this,
        extensions = extensions,
        lsp = lsp.extensionRequests,
        lspFacts = lsp.languageFacts,
        environmentManager = environmentManager,
        linuxEnvironment = linuxEnvironment,
        scope = viewModelScope,
    )

    // The terminal writes straight to the bind-mounted project directory,
    // entirely outside every method below - nothing here runs when a shell
    // command creates or deletes a file, so the explorer needs its own signal
    // that the disk changed. See ProjectFileWatcher's doc comment.
    private val fileWatcher = ProjectFileWatcher(onChanged = { dirs ->
        relistChangedDirs(dirs)
        // The watcher already debounces, and the refresh is single-flight
        // (see WorkspaceGitController.refresh), so bursts cannot pile up.
        git.refresh()
    })

    init {
        refreshTree()
        viewModelScope.launch {
            val ready = linuxEnvironment.isReady(environmentId)
            _uiState.update { it.copy(linuxReady = ready) }
        }
        terminals.newShell()
        fileWatcher.watch(projectFiles.projectRoot(projectId), emptySet())
        // Open tabs' folders must be watched even when collapsed, or outside edits go unseen.
        viewModelScope.launch { session.tabDirectories.collect { syncFileWatcher(_uiState.value.expandedDirs) } }
        session.start(settled)
    }

    private var attached = false

    /** On screen (again): extensions and commands target this workspace, and open files are re-checked against the disk. */
    fun onResumed() {
        extensionHost.attach()
        attached = true
        session.onResumed()
    }

    /** Off screen but alive: shells keep running, buffers stay in memory. */
    fun onParked() {
        extensionHost.detach()
        attached = false
    }

    suspend fun saveSession() = session.flush()

    fun endSession(discardStored: Boolean) = session.end(discardStored)

    override fun onCleared() {
        // Detaching resets the extension runtime's scope, which would clobber whichever workspace is on screen instead.
        if (attached) extensionHost.detach()
        terminals.release()
        fileWatcher.stop()
        lsp.release()
        git.release()
        super.onCleared()
    }

    // ---------------------------------------------------------------- tree

    fun refreshTree() {
        viewModelScope.launch {
            val nodes = projectFiles.list(projectId).getOrNull()
            // Re-list every expanded directory so a change deeper in the tree
            // is reflected without collapsing the user's expansion state -
            // all of them first, then one state update, so the explorer
            // recomposes once instead of once per directory.
            val children = _uiState.value.expandedDirs.mapNotNull { dir ->
                projectFiles.list(projectId, dir).getOrNull()?.let { dir to it }
            }.toMap()
            _uiState.update {
                it.copy(tree = nodes ?: it.tree, childrenByDir = it.childrenByDir + children)
            }
            if (nodes != null) launch(Dispatchers.Default) { TextMateHighlighter.prewarm(nodes.map { it.name }) }
        }
    }

    /**
     * [ProjectFileWatcher]'s targeted counterpart to [refreshTree]: re-lists
     * only the directories that actually fired a filesystem event, instead of
     * every expanded directory - a `touch` two levels deep otherwise re-reads
     * the whole visible tree from disk for no reason.
     */
    private fun relistChangedDirs(dirs: Set<File>) {
        val root = projectFiles.projectRoot(projectId)
        session.checkChangedDirs(dirs.mapTo(HashSet()) { it.relativeTo(root).path.replace(File.separatorChar, '/').let { rel -> if (rel == ".") "" else rel } })
        viewModelScope.launch {
            dirs.forEach { dir ->
                val relative = dir.relativeTo(root).path.replace(File.separatorChar, '/')
                if (relative.isEmpty() || relative == ".") {
                    projectFiles.list(projectId).onSuccess { nodes ->
                        _uiState.update { it.copy(tree = nodes) }
                    }
                } else if (relative in _uiState.value.expandedDirs) {
                    projectFiles.list(projectId, relative).onSuccess { children ->
                        _uiState.update { it.copy(childrenByDir = it.childrenByDir + (relative to children)) }
                    }
                }
            }
        }
    }

    fun onDirectoryToggled(node: FileNode) {
        val expanded = _uiState.value.expandedDirs
        if (node.relativePath in expanded) {
            val remaining = expanded - node.relativePath
            _uiState.update { it.copy(expandedDirs = remaining) }
            syncFileWatcher(remaining)
            return
        }
        viewModelScope.launch {
            projectFiles.list(projectId, node.relativePath).onSuccess { children ->
                val expandedNow = _uiState.value.expandedDirs + node.relativePath
                _uiState.update {
                    it.copy(
                        expandedDirs = expandedNow,
                        childrenByDir = it.childrenByDir + (node.relativePath to children),
                    )
                }
                syncFileWatcher(expandedNow)
            }
        }
    }

    private fun syncFileWatcher(expandedDirs: Set<String>) {
        val root = projectFiles.projectRoot(projectId)
        fileWatcher.watch(root, (expandedDirs + session.openTabDirectories()).map { File(root, it) }.toSet())
    }

    // -------------------------------------------------------------- editor

    fun onFileOpened(node: FileNode) {
        if (_uiState.value.openTabs.any { it.relativePath == node.relativePath }) {
            _uiState.update { it.copy(activeTabPath = node.relativePath) }
            return
        }
        viewModelScope.launch {
            projectFiles.open(projectId, node.relativePath)
                .onSuccess { content -> openContent(node, content) }
                .onFailure { cause -> setStatus(cause.message ?: "Could not open ${node.name}") }
        }
    }

    /**
     * Opens by project-relative path, for callers that hold a path rather than
     * a tree node - the source-control panel lists changes git reported, which
     * may not be in the (lazily expanded) tree at all.
     */
    fun openFileByPath(relativePath: String) {
        if (_uiState.value.openTabs.any { it.relativePath == relativePath }) {
            _uiState.update { it.copy(activeTabPath = relativePath) }
            return
        }
        val node = FileNode(
            name = relativePath.substringAfterLast('/'),
            relativePath = relativePath,
            isDirectory = false,
            sizeBytes = 0,
        )
        viewModelScope.launch {
            projectFiles.open(projectId, relativePath)
                .onSuccess { content -> openContent(node, content) }
                .onFailure { cause -> setStatus(cause.message ?: "Could not open $relativePath") }
        }
    }

    override suspend fun openAndAwait(relativePath: String): Boolean {
        if (_uiState.value.openTabs.any { it.relativePath == relativePath }) {
            _uiState.update { it.copy(activeTabPath = relativePath) }
            return true
        }
        val node = FileNode(relativePath.substringAfterLast('/'), relativePath, isDirectory = false, sizeBytes = 0)
        return projectFiles.open(projectId, relativePath).map { openContent(node, it) }.getOrDefault(false)
    }

    override fun replaceContent(path: String, content: String): Boolean {
        val tab = _uiState.value.openTabs.find { it.relativePath == path }?.takeIf { it.editable } ?: return false
        if (tab.content != content) {
            editing.onContentChanged(path, tab.content, content)
            updateTab(path) { it.copy(content = content) }
        }
        return true
    }

    override suspend fun writeClosedFile(path: String, content: String): Boolean =
        projectFiles.writeText(projectId, path, content).onSuccess { externalMirror.write(path, content.toByteArray()) }.isSuccess

    override suspend fun readClosedFile(path: String): String? =
        (projectFiles.open(projectId, path).getOrNull() as? FileContent.Text)?.takeIf { it.editable && !it.truncated }?.text

    private fun openContent(node: FileNode, content: FileContent): Boolean {
        val tab = editorTabFor(node, content, ::setStatus) ?: return false
        _uiState.update { it.copy(openTabs = it.openTabs + tab, activeTabPath = tab.relativePath) }
        return true
    }

    fun onTabSelected(path: String) = _uiState.update { it.copy(activeTabPath = path) }

    fun onTabClosed(path: String) {
        decorations.remove(path)
        editing.onTabClosed(path)
        selections.remove(path)
        _uiState.update { state ->
            val remaining = state.openTabs.filterNot { it.relativePath == path }
            state.copy(
                openTabs = remaining,
                activeTabPath = if (state.activeTabPath == path) {
                    remaining.lastOrNull()?.relativePath
                } else {
                    state.activeTabPath
                },
            )
        }
    }

    fun onContentChanged(path: String, content: String) {
        // Every buffer change passes here (typing, language server, extensions, undo), which is
        // what lets undo history see edits the text field never reported.
        _uiState.value.openTabs.find { it.relativePath == path }?.takeIf { it.editable }
            ?.let { editing.onContentChanged(path, it.content, content) }
        updateTab(path) { tab -> if (tab.editable) tab.copy(content = content) else tab }
    }

    fun onTogglePreview() {
        val path = _uiState.value.activeTabPath ?: return
        updateTab(path) { it.copy(showPreview = !it.showPreview) }
    }

    fun onSaveActiveTab() {
        val tab = _uiState.value.activeTab ?: return
        if (!tab.editable) {
            setStatus("${tab.name} is read-only")
            return
        }
        viewModelScope.launch { saveTab(tab) }
    }

    /**
     * Saves the dirty tabs among [paths], then runs [onSaved] only if every
     * write succeeded - the caller is about to drop those buffers, so a failed
     * save must keep them (and the status message says why).
     */
    fun onSaveTabs(paths: Collection<String>, onSaved: () -> Unit) {
        val dirty = _uiState.value.openTabs.filter { it.isDirty && it.relativePath in paths }
        viewModelScope.launch {
            if (dirty.map { saveTab(it) }.all { it }) onSaved()
        }
    }

    /** Save participants (format on save, code actions on save) run first and may change the text. */
    private suspend fun saveTab(tab: EditorTab): Boolean {
        session.blockedSave(tab)?.let {
            setStatus(it)
            return false
        }
        val text = lsp.beforeSave(tab)
        return projectFiles.writeText(projectId, tab.relativePath, text)
            .onSuccess {
                updateTab(tab.relativePath) { it.copy(savedContent = text, externalState = ExternalState.InSync) }
                lsp.afterSave(tab.relativePath, text)
                setStatus("Saved ${tab.name}")
                // No refreshTree(): only the root and expanded directories
                // are visible, and ProjectFileWatcher watches exactly
                // those, so its CLOSE_WRITE event already re-lists (and
                // refreshes git) for any save the explorer can show.
                externalMirror.write(tab.relativePath, text.toByteArray())
            }
            .onFailure { cause -> setStatus(cause.message ?: "Could not save ${tab.name}") }
            .isSuccess
    }

    /** A tab that is not a project file (an environment file opened by navigation), or selects it. */
    private fun openVirtualTab(tab: EditorTab) = _uiState.update { state ->
        if (state.openTabs.any { it.relativePath == tab.relativePath }) state.copy(activeTabPath = tab.relativePath)
        else state.copy(openTabs = state.openTabs + tab, activeTabPath = tab.relativePath)
    }

    private fun updateTab(path: String, transform: (EditorTab) -> EditorTab) {
        _uiState.update { state ->
            state.copy(openTabs = state.openTabs.map { if (it.relativePath == path) transform(it) else it })
        }
    }

    // ------------------------------------------------------- file actions

    fun onCreateFile(parentDir: String, name: String) = fileActions.createFile(parentDir, name)
    fun onCreateFolder(parentDir: String, name: String) = fileActions.createFolder(parentDir, name)
    fun onRename(node: FileNode, newName: String) = fileActions.rename(node, newName)
    fun onDelete(node: FileNode) = fileActions.delete(node)
    fun onCopyToClipboard(node: FileNode, cut: Boolean) = fileActions.copyToClipboard(node, cut)
    fun onPaste(targetDir: String) = fileActions.paste(targetDir)

    // ---------------------------------------------------- external folder

    private val externalMirror = WorkspaceExternalMirror(
        projectManager = projectManager,
        projectId = projectId,
        scope = viewModelScope,
        onSyncFailed = { reason -> setStatus("Folder sync: $reason") },
    )

    /** Absolute path as seen from inside the sandbox, which is what a shell needs. */
    fun absolutePathOf(node: FileNode): String =
        listOf(GUEST_WORKSPACE, node.relativePath).joinToString("/").replace("//", "/")

    private val fileActions = WorkspaceFileActions(
        projectId = projectId,
        projectFiles = projectFiles,
        state = _uiState,
        scope = viewModelScope,
        mirror = externalMirror,
        host = object : FileActionHost {
            override fun refreshTree() = this@WorkspaceViewModel.refreshTree()
            override fun setStatus(message: String) = this@WorkspaceViewModel.setStatus(message)
            override fun closeTab(path: String) = onTabClosed(path)

            // The per-path stores that live outside the session follow a tab that moved.
            override fun followRename(from: String, to: String) = session.followRename(from, to) { old, new ->
                decorations.rename(old, new)
                selections.rename(old, new)
                editing.onRenamed(old, new)
            }

            override fun forget(path: String) = editing.onDeleted(path)
        },
    )

    // ----------------------------------------------------------- terminals

    fun onNewTerminal() = terminals.newShell()

    fun revealTerminal() = terminals.reveal()

    fun onRenameTerminal(id: String, title: String) = terminals.rename(id, title)

    fun onSelectTerminal(id: String) = terminals.select(id)

    fun onCloseTerminal(id: String) = terminals.close(id)

    // ---- source control ----------------------------------------------------

    fun refreshGit() = git.refresh()

    fun onGitMessageChanged(message: String) = git.onMessageChanged(message)

    fun stageGit(paths: Collection<String>) = git.stage(paths)

    fun unstageGit(paths: Collection<String>) = git.unstage(paths)

    fun discardGit(paths: Collection<String>) = git.discard(paths)

    fun initGitRepository() = git.initRepository()

    fun commitGit() = git.commit()

    /** Remote, branch, stash, diff and commit-box actions beyond the basic callbacks above. */
    val gitControllers get() = git.controllers

    // ------------------------------------------------------------- linux

    fun onInstallLinux() {
        if (_uiState.value.isInstalling || _uiState.value.linuxReady) return
        val targetTabId = _uiState.value.activeTerminalId
        _uiState.update { it.copy(isInstalling = true) }
        log.log(LogLevel.INFO, LogSource.SANDBOX, "installing environment $environmentId")

        viewModelScope.launch {
            // Progress lines print straight into the terminal tab that was
            // active when install started - real scrolling log output, not a
            // Snackbar (install() reports dozens of lines: download %, every
            // apt/dpkg line) and not a single truncated status line either.
            // `write()` is stdin and would be typed *at* the shell; this goes
            // through the emulator's own output path instead, exactly the way
            // real process output reaches the screen - see WorkspaceTerminals.appendInstallLog.
            linuxEnvironment.install(environmentId, imageProvider(environmentId)) { line ->
                terminals.appendInstallLog(targetTabId, line)
            }
                .onSuccess {
                    environmentManager.markProvisioned(environmentId)
                    log.log(LogLevel.INFO, LogSource.SANDBOX, "environment $environmentId installed")
                    setStatus("Linux ready. Open a new terminal tab to use it.")
                }
                .onFailure { cause ->
                    environmentManager.markProvisioned(environmentId, cause.message ?: INSTALL_FAILED)
                    log.log(LogLevel.ERROR, LogSource.SANDBOX, "installing environment $environmentId failed: ${cause.message}")
                    setStatus("Install failed: ${cause.message}")
                }
            val ready = linuxEnvironment.isReady(environmentId)
            _uiState.update { it.copy(isInstalling = false, linuxReady = ready) }
        }
    }

    fun onStatusShown() = _uiState.update { it.copy(statusMessage = null) }

    private fun setStatus(message: String) = _uiState.update { it.copy(statusMessage = message) }

    private companion object {
        const val GUEST_WORKSPACE = "/workspace"
        const val INSTALL_FAILED = "install failed"
    }
}
