package dev.easyide.app.ui.screens.workspace

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.easyide.app.extensions.ExtensionsContainer
import dev.easyide.app.lsp.LspRuntime
import dev.easyide.app.ui.screens.workspace.decor.DecorationRegistry
import dev.easyide.app.ui.screens.workspace.ext.EditorBuffers
import dev.easyide.app.ui.screens.workspace.ext.WorkspaceExtensionHost
import dev.easyide.app.ui.screens.workspace.lsp.LspWorkspaceHost
import dev.easyide.app.ui.screens.workspace.lsp.WorkspaceLspController
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

    val terminals = WorkspaceTerminals(
        _uiState, viewModelScope, appContext, linuxEnvironment, environmentId, projectFiles.projectRoot(projectId), ::setStatus,
    )

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
        projectRoot = projectFiles.projectRoot(projectId),
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
        extensionHost.attach()
    }

    override fun onCleared() {
        extensionHost.detach()
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
        fileWatcher.watch(root, expandedDirs.map { File(root, it) }.toSet())
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
        if (tab.content != content) updateTab(path) { it.copy(content = content) }
        return true
    }

    override suspend fun writeClosedFile(path: String, content: String): Boolean =
        projectFiles.writeText(projectId, path, content).onSuccess { externalMirror.write(path, content.toByteArray()) }.isSuccess

    override suspend fun readClosedFile(path: String): String? =
        (projectFiles.open(projectId, path).getOrNull() as? FileContent.Text)?.takeIf { it.editable && !it.truncated }?.text

    private fun openContent(node: FileNode, content: FileContent): Boolean {
        val tab = when (content) {
            is FileContent.Rejected -> {
                setStatus(content.reason)
                return false
            }

            is FileContent.BinaryPreview -> EditorTab(
                relativePath = node.relativePath,
                name = node.name,
                content = content.hexDump,
                savedContent = content.hexDump,
                editable = false,
                highlightingEnabled = false,
                notice = "Binary (${FilePolicy.humanSize(content.totalBytes)}) - read-only preview",
            )

            is FileContent.Text -> EditorTab(
                relativePath = node.relativePath,
                name = node.name,
                content = content.text,
                savedContent = content.text,
                editable = content.editable,
                highlightingEnabled = content.highlightingEnabled,
                notice = textNotice(content),
                // Markdown opens in preview, matching how it is usually read.
                showPreview = node.name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown"),
            )
        }
        _uiState.update { it.copy(openTabs = it.openTabs + tab, activeTabPath = tab.relativePath) }
        return true
    }

    private fun textNotice(content: FileContent.Text): String? = when {
        content.truncated -> "First ${FilePolicy.humanSize(FilePolicy.TEXT_VIEW_PREFIX_BYTES)} " +
            "of ${FilePolicy.humanSize(content.totalBytes)} - read-only"

        !content.editable -> "${FilePolicy.humanSize(content.totalBytes)} - read-only"
        !content.highlightingEnabled -> "Large file - syntax highlighting off"
        else -> null
    }

    fun onTabSelected(path: String) = _uiState.update { it.copy(activeTabPath = path) }

    fun onTabClosed(path: String) {
        decorations.remove(path)
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

    fun onContentChanged(path: String, content: String) = updateTab(path) { tab ->
        if (tab.editable) tab.copy(content = content) else tab
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
        val text = lsp.beforeSave(tab)
        return projectFiles.writeText(projectId, tab.relativePath, text)
            .onSuccess {
                updateTab(tab.relativePath) { it.copy(savedContent = text) }
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

    fun onCreateFile(parentDir: String, name: String) {
        val path = joinPath(parentDir, name)
        runFileAction(name) {
            projectFiles.createFile(projectId, path).onSuccess { externalMirror.write(path, ByteArray(0)) }
        }
    }

    fun onCreateFolder(parentDir: String, name: String) {
        val path = joinPath(parentDir, name)
        runFileAction(name) {
            projectFiles.createDirectory(projectId, path).onSuccess { externalMirror.createDirectory(path) }
        }
    }

    fun onRename(node: FileNode, newName: String) {
        viewModelScope.launch {
            projectFiles.rename(projectId, node.relativePath, newName)
                .onSuccess { newPath ->
                    // An open tab still points at the old path; retarget it so
                    // saving does not recreate the file under its old name.
                    updateTab(node.relativePath) { it.copy(relativePath = newPath, name = newName) }
                    decorations.rename(node.relativePath, newPath)
                    selections.rename(node.relativePath, newPath)
                    _uiState.update { state ->
                        state.copy(
                            activeTabPath = if (state.activeTabPath == node.relativePath) newPath else state.activeTabPath,
                        )
                    }
                    refreshTree()
                    externalMirror.delete(node.relativePath)
                    externalMirror.path(newPath)
                }
                .onFailure { cause -> setStatus(cause.message ?: "Could not rename ${node.name}") }
        }
    }

    fun onDelete(node: FileNode) {
        viewModelScope.launch {
            projectFiles.delete(projectId, node.relativePath)
                .onSuccess {
                    onTabClosed(node.relativePath)
                    refreshTree()
                    setStatus("Deleted ${node.name}")
                    externalMirror.delete(node.relativePath)
                }
                .onFailure { cause -> setStatus(cause.message ?: "Could not delete ${node.name}") }
        }
    }

    fun onCopyToClipboard(node: FileNode, cut: Boolean) {
        _uiState.update { it.copy(clipboard = FileClipboard(node.relativePath, cut)) }
        setStatus(if (cut) "Cut ${node.name}" else "Copied ${node.name}")
    }

    fun onPaste(targetDir: String) {
        val clipboard = _uiState.value.clipboard ?: return
        viewModelScope.launch {
            val result = if (clipboard.isCut) {
                projectFiles.move(projectId, clipboard.relativePath, targetDir)
            } else {
                projectFiles.copy(projectId, clipboard.relativePath, targetDir)
            }
            result
                .onSuccess { newPath ->
                    if (clipboard.isCut) _uiState.update { it.copy(clipboard = null) }
                    refreshTree()
                    if (clipboard.isCut) externalMirror.delete(clipboard.relativePath)
                    externalMirror.path(newPath)
                }
                .onFailure { cause -> setStatus(cause.message ?: "Paste failed") }
        }
    }

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

    private fun runFileAction(name: String, action: suspend () -> Result<Unit>) {
        if (name.isBlank()) return
        viewModelScope.launch {
            action()
                .onSuccess { refreshTree() }
                .onFailure { cause -> setStatus(cause.message ?: "Could not create $name") }
        }
    }

    private fun joinPath(parentDir: String, name: String): String =
        if (parentDir.isEmpty()) name else "$parentDir/$name"

    // ----------------------------------------------------------- terminals

    fun onNewTerminal() = terminals.newShell()

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
                    setStatus("Linux ready. Open a new terminal tab to use it.")
                }
                .onFailure { cause ->
                    environmentManager.markProvisioned(environmentId, cause.message ?: INSTALL_FAILED)
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
