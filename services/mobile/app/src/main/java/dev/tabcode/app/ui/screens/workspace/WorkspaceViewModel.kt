package dev.tabcode.app.ui.screens.workspace

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tabcode.sandbox.EnvironmentManager
import dev.tabcode.sandbox.LinuxEnvironment
import dev.tabcode.sandbox.ProjectManager
import dev.tabcode.sandbox.files.FileContent
import dev.tabcode.sandbox.files.FileNode
import dev.tabcode.sandbox.files.FilePolicy
import dev.tabcode.sandbox.files.ProjectFiles
import dev.tabcode.sandbox.model.SandboxImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.coroutineContext

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
    val terminals: List<TerminalSession> = emptyList(),
    val activeTerminalId: String? = null,
    val statusMessage: String? = null,
    val linuxReady: Boolean = false,
    val isInstalling: Boolean = false,
    val clipboard: FileClipboard? = null,
) {
    val activeTab: EditorTab? get() = openTabs.find { it.relativePath == activeTabPath }
    val activeTerminal: TerminalSession? get() = terminals.find { it.id == activeTerminalId }
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
    private val imageProvider: suspend (String) -> SandboxImage,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    private val terminalJobs = TerminalJobs()

    init {
        refreshTree()
        val ready = linuxEnvironment.isReady(environmentId)
        val first = WorkspaceTerminals.newSession(0, UUID.randomUUID().toString(), banner(ready))
        _uiState.update {
            it.copy(linuxReady = ready, terminals = listOf(first), activeTerminalId = first.id)
        }
    }

    override fun onCleared() {
        terminalJobs.cancelAll()
        super.onCleared()
    }

    private fun banner(ready: Boolean) = if (ready) {
        "Ubuntu sandbox - apt, dpkg, sudo available"
    } else {
        "Android shell (toybox). Use \"Install Linux\" for apt/dpkg."
    }

    // ---------------------------------------------------------------- tree

    fun refreshTree() {
        viewModelScope.launch {
            projectFiles.list(projectId).onSuccess { nodes ->
                _uiState.update { it.copy(tree = nodes) }
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

    fun onDirectoryToggled(node: FileNode) {
        val expanded = _uiState.value.expandedDirs
        if (node.relativePath in expanded) {
            _uiState.update { it.copy(expandedDirs = expanded - node.relativePath) }
            return
        }
        viewModelScope.launch {
            projectFiles.list(projectId, node.relativePath).onSuccess { children ->
                _uiState.update {
                    it.copy(
                        expandedDirs = it.expandedDirs + node.relativePath,
                        childrenByDir = it.childrenByDir + (node.relativePath to children),
                    )
                }
            }
        }
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
                    mirrorWrite(tab.relativePath, tab.content.toByteArray())
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
            projectFiles.createFile(projectId, path).onSuccess { mirrorWrite(path, ByteArray(0)) }
        }
    }

    fun onCreateFolder(parentDir: String, name: String) {
        val path = joinPath(parentDir, name)
        runFileAction(name) {
            projectFiles.createDirectory(projectId, path).onSuccess { mirrorCreateDirectory(path) }
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
                    mirrorDelete(node.relativePath)
                    mirrorPath(newPath)
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
                    mirrorDelete(node.relativePath)
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
                    if (clipboard.isCut) mirrorDelete(clipboard.relativePath)
                    mirrorPath(newPath)
                }
                .onFailure { cause -> setStatus(cause.message ?: "Paste failed") }
        }
    }

    // ---------------------------------------------------- external folder

    /**
     * Every mirror call below is fire-and-forget on purpose: the app-private
     * write this follows has already succeeded by the time these run, so a
     * sync problem (permission revoked, SD card removed, provider rejected
     * the write) is surfaced as a status message, never as a failure of the
     * save/create/rename/delete the user actually asked for.
     */
    private fun mirrorWrite(relativePath: String, content: ByteArray) {
        viewModelScope.launch {
            projectManager.mirrorWrite(projectId, relativePath, content).onFailure(::reportSyncFailure)
        }
    }

    private fun mirrorCreateDirectory(relativePath: String) {
        viewModelScope.launch {
            projectManager.mirrorCreateDirectory(projectId, relativePath).onFailure(::reportSyncFailure)
        }
    }

    private fun mirrorDelete(relativePath: String) {
        viewModelScope.launch {
            projectManager.mirrorDelete(projectId, relativePath).onFailure(::reportSyncFailure)
        }
    }

    private fun mirrorPath(relativePath: String) {
        viewModelScope.launch {
            projectManager.mirrorPath(projectId, relativePath).onFailure(::reportSyncFailure)
        }
    }

    private fun reportSyncFailure(cause: Throwable) {
        setStatus("Folder sync: ${cause.message ?: "failed"}")
    }

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

    fun onTerminalInputChanged(value: TextFieldValue) {
        val id = _uiState.value.activeTerminalId ?: return
        updateTerminal(id) { it.copy(input = value, historyCursor = null) }
    }

    /** A press on the accessory row: either types a symbol or drives the prompt. */
    fun onTerminalKey(key: TerminalKey) {
        val session = _uiState.value.activeTerminal ?: return
        when (key) {
            is TerminalKey.Insert -> updateTerminal(session.id) {
                it.copy(input = TerminalInput.insert(it.input, key.text), historyCursor = null)
            }

            is TerminalKey.Command -> when (key.action) {
                TerminalKeyAction.CARET_LEFT ->
                    updateTerminal(session.id) { it.copy(input = TerminalInput.moveCaret(it.input, -1)) }

                TerminalKeyAction.CARET_RIGHT ->
                    updateTerminal(session.id) { it.copy(input = TerminalInput.moveCaret(it.input, 1)) }

                TerminalKeyAction.HISTORY_BACK ->
                    updateTerminal(session.id) { WorkspaceTerminals.historyUp(it) }

                TerminalKeyAction.HISTORY_FORWARD ->
                    updateTerminal(session.id) { WorkspaceTerminals.historyDown(it) }

                TerminalKeyAction.INTERRUPT -> onCancelCommand()

                TerminalKeyAction.CLEAR -> updateTerminal(session.id) { it.copy(lines = emptyList()) }
            }
        }
    }

    /**
     * Enter does one of two things: with nothing running it starts a command;
     * with a command running it feeds the line to that process's stdin, which
     * is what makes interactive programs answerable.
     */
    fun onTerminalSubmit() {
        val session = _uiState.value.activeTerminal ?: return
        val typed = session.input.text

        if (session.isRunning) {
            val process = terminalJobs.processFor(session.id) ?: return
            process.send(typed)
            // Echo it: the child's stdin is not echoed back to us, so without
            // this the user's own typing would vanish.
            updateTerminal(session.id) {
                it.appended(listOf(TerminalLine(typed, isCommand = true)), WorkspaceTerminals.MAX_LINES)
                    .copy(input = TextFieldValue())
            }
            return
        }

        val command = typed.trim()
        if (command.isEmpty()) return
        updateTerminal(session.id) { WorkspaceTerminals.startCommand(it, command) }
        launchCommand(session.id, command)
    }

    /**
     * Streams the command's output as it arrives instead of buffering it to
     * completion, so a long build shows progress and never looks frozen.
     * Batching happens inside [dev.tabcode.sandbox.shell.TerminalProcess].
     */
    private fun launchCommand(sessionId: String, command: String) {
        viewModelScope.launch {
            val process = runCatching {
                linuxEnvironment.start(
                    command = command,
                    environmentId = environmentId,
                    hostProjectDir = projectFiles.projectRoot(projectId),
                )
            }.getOrElse { cause ->
                appendToTerminal(sessionId, listOf(TerminalLine(cause.message ?: "failed to start", false)))
                updateTerminal(sessionId) { it.copy(isRunning = false) }
                return@launch
            }

            terminalJobs.put(sessionId, coroutineContext[Job]!!, process)

            val exit = process.stream { lines ->
                // Capped: a single pathological line (a minified bundle, a
                // progress bar with no newlines) would otherwise be laid out in
                // full on the UI thread.
                appendToTerminal(
                    sessionId,
                    lines.map { TerminalLine(it.take(WorkspaceTerminals.MAX_LINE_LENGTH), isCommand = false) },
                )
            }
            if (exit != 0) {
                appendToTerminal(sessionId, listOf(TerminalLine("[exit $exit]", isCommand = false)))
            }

            terminalJobs.finish(sessionId)
            updateTerminal(sessionId) { it.copy(isRunning = false) }
            // A command may have created or removed files.
            refreshTree()
        }
    }

    private fun appendToTerminal(sessionId: String, lines: List<TerminalLine>) {
        if (lines.isEmpty()) return
        updateTerminal(sessionId) { it.appended(lines, WorkspaceTerminals.MAX_LINES) }
    }

    fun onCancelCommand() {
        val id = _uiState.value.activeTerminalId ?: return
        terminalJobs.cancel(id)
        updateTerminal(id) {
            it.appended(listOf(TerminalLine("^C", isCommand = false)), WorkspaceTerminals.MAX_LINES)
                .copy(isRunning = false)
        }
    }

    fun onNewTerminal() {
        _uiState.update { state ->
            val session = WorkspaceTerminals.newSession(
                index = state.terminals.size,
                id = UUID.randomUUID().toString(),
                banner = banner(state.linuxReady),
            )
            state.copy(terminals = state.terminals + session, activeTerminalId = session.id)
        }
    }

    fun onSelectTerminal(id: String) = _uiState.update { it.copy(activeTerminalId = id) }

    fun onCloseTerminal(id: String) {
        terminalJobs.cancel(id)
        _uiState.update { state ->
            val (remaining, active) = WorkspaceTerminals.close(state.terminals, state.activeTerminalId, id)
            state.copy(terminals = remaining, activeTerminalId = active)
        }
    }

    private fun updateTerminal(id: String, transform: (TerminalSession) -> TerminalSession) {
        _uiState.update { it.copy(terminals = WorkspaceTerminals.updateSession(it.terminals, id, transform)) }
    }

    // ------------------------------------------------------------- linux

    fun onInstallLinux() {
        if (_uiState.value.isInstalling || _uiState.value.linuxReady) return
        _uiState.update { it.copy(isInstalling = true) }
        val terminalId = _uiState.value.activeTerminalId

        viewModelScope.launch {
            linuxEnvironment.install(environmentId, imageProvider(environmentId)) { message ->
                terminalId?.let { id ->
                    updateTerminal(id) {
                        it.appended(
                            listOf(
                                TerminalLine(
                                    message.take(WorkspaceTerminals.MAX_LINE_LENGTH),
                                    isCommand = false,
                                )
                            ),
                            WorkspaceTerminals.MAX_LINES,
                        )
                    }
                }
            }.onSuccess {
                environmentManager.markProvisioned(environmentId)
            }.onFailure { cause ->
                environmentManager.markProvisioned(environmentId, cause.message ?: INSTALL_FAILED)
                terminalId?.let { id ->
                    updateTerminal(id) {
                        it.appended(
                            listOf(TerminalLine("install failed: ${cause.message}", isCommand = false)),
                            WorkspaceTerminals.MAX_LINES,
                        )
                    }
                }
            }
            _uiState.update {
                it.copy(isInstalling = false, linuxReady = linuxEnvironment.isReady(environmentId))
            }
        }
    }

    fun onStatusShown() = _uiState.update { it.copy(statusMessage = null) }

    private fun setStatus(message: String) = _uiState.update { it.copy(statusMessage = message) }

    private companion object {
        const val GUEST_WORKSPACE = "/workspace"
        const val INSTALL_FAILED = "install failed"
    }
}
