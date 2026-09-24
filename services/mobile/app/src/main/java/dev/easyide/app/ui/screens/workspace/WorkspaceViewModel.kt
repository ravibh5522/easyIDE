package dev.easyide.app.ui.screens.workspace

import android.content.Context
import com.termux.terminal.TerminalSession
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.easyide.app.lsp.LspRuntime
import dev.easyide.app.ui.screens.workspace.decor.DecorationRegistry
import dev.easyide.app.ui.screens.workspace.lsp.LspWorkspaceHost
import dev.easyide.app.ui.screens.workspace.lsp.WorkspaceLspController
import dev.easyide.sandbox.EnvironmentManager
import dev.easyide.sandbox.LinuxEnvironment
import dev.easyide.sandbox.ProjectManager
import dev.easyide.sandbox.files.FileContent
import dev.easyide.sandbox.files.FileNode
import dev.easyide.sandbox.files.FilePolicy
import dev.easyide.sandbox.git.GitService
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
import java.util.UUID

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
    lspRuntime: LspRuntime,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    private val git = WorkspaceGitController(gitService, projectFiles.projectRoot(projectId), viewModelScope)
    val gitState: StateFlow<GitPanelState> = git.state

    /** Per-document editor decorations; producers (LSP, find) write, `EditorPane` paints. */
    val decorations = DecorationRegistry()
    private val installLog = InstallLogPump { _uiState.value.terminals }

    /** Language servers for this workspace's tabs: documents, decorations, popups, panels. */
    val lsp = WorkspaceLspController(
        runtime = lspRuntime,
        host = object : LspWorkspaceHost {
            override val state: StateFlow<WorkspaceUiState> get() = uiState
            override fun setContent(path: String, text: String) = onContentChanged(path, text)
            override fun openProjectFile(path: String) = openFileByPath(path)
            override fun openTab(tab: EditorTab) = openVirtualTab(tab)
            override fun showStatus(message: String) = setStatus(message)
            override fun runInTerminal(command: String) = createTerminalTab(initialCommand = command)
            override fun string(id: Int, vararg args: Any): String = appContext.getString(id, *args)
        },
        decorations = decorations,
        scope = viewModelScope,
        environmentId = environmentId,
        projectId = projectId,
        projectRoot = projectFiles.projectRoot(projectId),
        rootfsDir = lspRuntime.rootfsDir(environmentId),
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
        createTerminalTab()
        fileWatcher.watch(projectFiles.projectRoot(projectId), emptySet())
    }

    override fun onCleared() {
        // A pty subprocess is a real Linux process, not something garbage
        // collection reclaims - it needs an explicit SIGKILL or it keeps
        // running (and holding the pty) after the workspace is gone.
        _uiState.value.terminals.forEach { it.session.finishIfRunning() }
        fileWatcher.stop()
        installLog.stop()
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

    private fun openContent(node: FileNode, content: FileContent) {
        val tab = when (content) {
            is FileContent.Rejected -> {
                setStatus(content.reason)
                return
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
    //
    // There is no input-mediation left here (no prompt buffer, no history, no
    // per-tab "is a command running" flag) - a real TerminalSession/TerminalView
    // owns typing, scrollback and process state entirely (see TerminalPane).
    // The ViewModel's job is just the tab list: create one on request, retire
    // its process when a tab closes or the workspace does, and keep the tab
    // title in step with what the shell itself reports.

    /**
     * Resolves proot-or-fallback shell params off the main thread, then
     * constructs the real `TerminalSession` and adds it as a new tab. Async
     * because [LinuxEnvironment.interactiveShellParams] may need to install
     * proot on first use - the same reason [onInstallLinux] is a coroutine.
     */
    /** [initialCommand] is typed into the new shell (an install recipe the user asked to run). */
    private fun createTerminalTab(initialCommand: String? = null) {
        viewModelScope.launch {
            val params = runCatching {
                linuxEnvironment.interactiveShellParams(environmentId, projectFiles.projectRoot(projectId))
            }.getOrElse { cause ->
                setStatus(cause.message ?: "Could not start a terminal")
                return@launch
            }

            val id = UUID.randomUUID().toString()
            val client = EasyTerminalSessionClient(
                context = appContext,
                onTitleChanged = { changed -> retitleTerminal(id, changed.title) },
                onSessionFinished = { /* frozen scrollback with the exit message is the desired end state */ },
            )
            val session = TerminalSession(
                params.shellPath,
                params.cwd,
                params.args.toTypedArray(),
                params.env.map { (key, value) -> "$key=$value" }.toTypedArray(),
                null,
                client,
            )
            val tab = PtyTerminalTab(
                id = id,
                title = "sh ${_uiState.value.terminals.size + 1}",
                session = session,
                client = client,
            )
            initialCommand?.let { session.write(it + "\n") }
            _uiState.update {
                it.copy(
                    terminals = it.terminals + tab,
                    activeTerminalId = tab.id,
                    terminalRevealRequests = it.terminalRevealRequests + if (initialCommand != null) 1 else 0,
                )
            }
        }
    }


    // ---- source control ----------------------------------------------------

    fun refreshGit() = git.refresh()

    fun onGitMessageChanged(message: String) = git.onMessageChanged(message)

    fun stageGit(paths: Collection<String>) = git.stage(paths)

    fun unstageGit(paths: Collection<String>) = git.unstage(paths)

    fun discardGit(paths: Collection<String>) = git.discard(paths)

    fun initGitRepository() = git.initRepository()

    fun commitGit() = git.commit()

    private fun retitleTerminal(id: String, title: String?) {
        if (title.isNullOrBlank()) return
        _uiState.update { state ->
            state.copy(terminals = state.terminals.map { if (it.id == id) it.copy(title = title) else it })
        }
    }

    fun onNewTerminal() = createTerminalTab()

    /** User-driven rename, distinct from [retitleTerminal] which tracks the shell's own OSC title. */
    fun onRenameTerminal(id: String, title: String) = retitleTerminal(id, title)

    fun onSelectTerminal(id: String) = _uiState.update { it.copy(activeTerminalId = id) }

    /** The last tab is never closed, so the panel always has something to show. */
    fun onCloseTerminal(id: String) {
        val state = _uiState.value
        if (state.terminals.size <= 1) return
        state.terminals.find { it.id == id }?.session?.finishIfRunning()
        _uiState.update { current ->
            val remaining = current.terminals.filterNot { it.id == id }
            val active = if (current.activeTerminalId == id) remaining.lastOrNull()?.id else current.activeTerminalId
            current.copy(terminals = remaining, activeTerminalId = active)
        }
    }

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
            // real process output reaches the screen - see InstallLogPump.
            linuxEnvironment.install(environmentId, imageProvider(environmentId)) { line ->
                installLog.append(targetTabId, line)
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
