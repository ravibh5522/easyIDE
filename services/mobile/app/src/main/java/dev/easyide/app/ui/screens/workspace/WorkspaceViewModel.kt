package dev.easyide.app.ui.screens.workspace

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.termux.terminal.TerminalSession
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.easyide.sandbox.EnvironmentManager
import dev.easyide.sandbox.LinuxEnvironment
import dev.easyide.sandbox.ProjectManager
import dev.easyide.sandbox.files.FileContent
import dev.easyide.sandbox.files.FileNode
import dev.easyide.sandbox.files.FilePolicy
import dev.easyide.sandbox.git.GitService
import dev.easyide.sandbox.git.GitResult
import dev.easyide.sandbox.git.GitStatus
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
 * An open editor tab. [savedContent] is what is on disk; [content] is the
 * buffer. A tab may be read-only (a binary preview, or a truncated view of a
 * file too large to edit safely), in which case it can never be dirty.
 */
data class EditorTab(
    val relativePath: String,
    val name: String,
    val content: String,
    val savedContent: String,
    val editable: Boolean = true,
    val highlightingEnabled: Boolean = true,
    val notice: String? = null,
    val showPreview: Boolean = false,
) {
    val isDirty: Boolean get() = editable && content != savedContent

    /** Markdown gets a preview toggle; nothing else has a renderer yet. */
    val isMarkdown: Boolean get() = name.substringAfterLast('.', "").lowercase() in MARKDOWN_EXTENSIONS

    private companion object {
        val MARKDOWN_EXTENSIONS = setOf("md", "markdown")
    }
}

/** A pending file copy/cut, populated by the explorer context menu. */
data class FileClipboard(val relativePath: String, val isCut: Boolean)

data class WorkspaceUiState(
    val projectName: String = "",
    val tree: List<FileNode> = emptyList(),
    val expandedDirs: Set<String> = emptySet(),
    val childrenByDir: Map<String, List<FileNode>> = emptyMap(),
    val openTabs: List<EditorTab> = emptyList(),
    val activeTabPath: String? = null,
    val terminals: List<PtyTerminalTab> = emptyList(),
    val activeTerminalId: String? = null,
    val statusMessage: String? = null,
    val linuxReady: Boolean = false,
    val isInstalling: Boolean = false,
    val clipboard: FileClipboard? = null,
) {
    val activeTab: EditorTab? get() = openTabs.find { it.relativePath == activeTabPath }
    val activeTerminal: PtyTerminalTab? get() = terminals.find { it.id == activeTerminalId }
}

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
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    private val _gitState = MutableStateFlow(GitPanelState())
    val gitState: StateFlow<GitPanelState> = _gitState.asStateFlow()
    private val mainHandler = Handler(Looper.getMainLooper())

    // The terminal writes straight to the bind-mounted project directory,
    // entirely outside every method below - nothing here runs when a shell
    // command creates or deletes a file, so the explorer needs its own signal
    // that the disk changed. See ProjectFileWatcher's doc comment.
    private val fileWatcher = ProjectFileWatcher(onChanged = { dirs ->
        relistChangedDirs(dirs)
        // The watcher already debounces, and git status is cheap next to the
        // re-list it fires alongside, so this needs no throttle of its own.
        refreshGit()
    })

    init {
        refreshTree()
        _uiState.update { it.copy(linuxReady = linuxEnvironment.isReady(environmentId)) }
        createTerminalTab()
        fileWatcher.watch(projectFiles.projectRoot(projectId), emptySet())
    }

    override fun onCleared() {
        // A pty subprocess is a real Linux process, not something garbage
        // collection reclaims - it needs an explicit SIGKILL or it keeps
        // running (and holding the pty) after the workspace is gone.
        _uiState.value.terminals.forEach { it.session.finishIfRunning() }
        fileWatcher.stop()
        super.onCleared()
    }

    // ---------------------------------------------------------------- tree

    fun refreshTree() {
        viewModelScope.launch {
            projectFiles.list(projectId).onSuccess { nodes ->
                _uiState.update { it.copy(tree = nodes) }
                launch(Dispatchers.Default) { TextMateHighlighter.prewarm(nodes.map { it.name }) }
            }
            // Re-list every expanded directory so a change deeper in the tree
            // is reflected without collapsing the user's expansion state.
            _uiState.value.expandedDirs.forEach { dir ->
                projectFiles.list(projectId, dir).onSuccess { children ->
                    _uiState.update { it.copy(childrenByDir = it.childrenByDir + (dir to children)) }
                }
            }
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
        viewModelScope.launch {
            projectFiles.writeText(projectId, tab.relativePath, tab.content)
                .onSuccess {
                    updateTab(tab.relativePath) { it.copy(savedContent = it.content) }
                    setStatus("Saved ${tab.name}")
                    refreshTree()
                    externalMirror.write(tab.relativePath, tab.content.toByteArray())
                }
                .onFailure { cause -> setStatus(cause.message ?: "Could not save ${tab.name}") }
        }
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
    private fun createTerminalTab() {
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
            _uiState.update { it.copy(terminals = it.terminals + tab, activeTerminalId = tab.id) }
        }
    }


    // ---- source control ----------------------------------------------------

    /**
     * Re-reads status (and history) from disk.
     *
     * Called on every watcher event, so it must stay cheap and must not fight
     * with itself: [GitPanelState.busy] gates the UI's own actions, not this,
     * because a refresh triggered by an external write (the terminal, Claude
     * Code) has to land even while a commit is in flight.
     */
    fun refreshGit() {
        val root = projectFiles.projectRoot(projectId)
        viewModelScope.launch {
            val isRepo = gitService.isRepository(root)
            if (!isRepo) {
                _gitState.update { it.copy(isRepository = false, status = null, commits = emptyList()) }
                return@launch
            }
            when (val result = gitService.status(root)) {
                is GitResult.Success -> {
                    val commits = gitService.log(root).valueOrNull().orEmpty()
                    _gitState.update {
                        it.copy(isRepository = true, status = result.value, commits = commits, error = null)
                    }
                }
                is GitResult.Failure ->
                    _gitState.update { it.copy(isRepository = true, error = result.message) }
                GitResult.NotARepository ->
                    _gitState.update { it.copy(isRepository = false, status = null) }
            }
        }
    }

    fun onGitMessageChanged(message: String) = _gitState.update { it.copy(commitMessage = message) }

    fun stageGit(paths: Collection<String>) = gitAction { gitService.stage(it, paths) }

    fun unstageGit(paths: Collection<String>) = gitAction { gitService.unstage(it, paths) }

    fun discardGit(paths: Collection<String>) = gitAction { gitService.discard(it, paths) }

    fun initGitRepository() = gitAction { gitService.createRepository(it) }

    /**
     * Commits, then clears the message only on success - a failed commit that
     * silently ate the message the user typed is the worst possible outcome.
     */
    fun commitGit() {
        val message = _gitState.value.commitMessage
        if (message.isBlank()) return
        gitAction(onSuccess = { _gitState.update { it.copy(commitMessage = "") } }) { root ->
            gitService.commit(root, message, GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL)
        }
    }

    private fun gitAction(
        onSuccess: () -> Unit = {},
        block: suspend (File) -> GitResult<GitStatus>,
    ) {
        val root = projectFiles.projectRoot(projectId)
        viewModelScope.launch {
            _gitState.update { it.copy(busy = true, error = null) }
            when (val result = block(root)) {
                is GitResult.Success -> {
                    onSuccess()
                    val commits = gitService.log(root).valueOrNull().orEmpty()
                    _gitState.update {
                        it.copy(isRepository = true, status = result.value, commits = commits, busy = false)
                    }
                }
                is GitResult.Failure ->
                    _gitState.update { it.copy(busy = false, error = result.message) }
                GitResult.NotARepository ->
                    _gitState.update { it.copy(busy = false, isRepository = false, status = null) }
            }
        }
    }

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
            // real process output reaches the screen - see appendInstallLog.
            linuxEnvironment.install(environmentId, imageProvider(environmentId)) { line ->
                appendInstallLog(targetTabId, line)
            }
                .onSuccess {
                    environmentManager.markProvisioned(environmentId)
                    setStatus("Linux ready. Open a new terminal tab to use it.")
                }
                .onFailure { cause ->
                    environmentManager.markProvisioned(environmentId, cause.message ?: INSTALL_FAILED)
                    setStatus("Install failed: ${cause.message}")
                }
            _uiState.update {
                it.copy(isInstalling = false, linuxReady = linuxEnvironment.isReady(environmentId))
            }
        }
    }

    /**
     * Feeds one line of install progress into a terminal's screen buffer as
     * if it were real process output - not `session.write()`, which is stdin
     * and would be typed *at* the shell. `install()`'s progress callback fires
     * from a background (IO) dispatcher, but `TerminalEmulator`/`TerminalBuffer`
     * are not thread-safe (Termux's own pty-read loop only ever touches them
     * from the main thread via its `Handler`), so this hops to the main
     * thread before touching either. Falls back to whatever terminal tab is
     * still open if the one active at install-start was since closed.
     */
    private fun appendInstallLog(tabId: String?, line: String) {
        mainHandler.post {
            val tab = _uiState.value.terminals.find { it.id == tabId }
                ?: _uiState.value.terminals.firstOrNull()
                ?: return@post
            val bytes = "$line\r\n".toByteArray()
            tab.session.emulator?.append(bytes, bytes.size)
            tab.client.onTextChanged(tab.session)
        }
    }

    fun onStatusShown() = _uiState.update { it.copy(statusMessage = null) }

    private fun setStatus(message: String) = _uiState.update { it.copy(statusMessage = message) }

    private companion object {
        const val GUEST_WORKSPACE = "/workspace"
        const val INSTALL_FAILED = "install failed"

        // Placeholder identity until a git settings screen exists; a commit
        // must have an author, and refusing to commit would be worse.
        const val GIT_AUTHOR_NAME = "easyIDE"
        const val GIT_AUTHOR_EMAIL = "dev@easyide.local"
    }
}
