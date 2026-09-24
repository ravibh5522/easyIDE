package dev.easyide.app.ui.screens.workspace

import dev.easyide.sandbox.git.GitResult
import dev.easyide.sandbox.git.GitService
import dev.easyide.sandbox.git.GitStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * The workspace's source-control state and actions, split out of
 * [WorkspaceViewModel]. Every method is called on the main thread (UI
 * callbacks and [dev.easyide.sandbox.files.ProjectFileWatcher]'s main-looper
 * flush), which is what lets the refresh bookkeeping below go unsynchronized.
 */
class WorkspaceGitController(
    private val gitService: GitService,
    private val projectRoot: File,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(GitPanelState())
    val state: StateFlow<GitPanelState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var refreshAgain = false

    /**
     * Re-reads status (and history) from disk.
     *
     * Called on every watcher event, so it must stay cheap and must not fight
     * with itself: at most one refresh runs at a time, and any number of
     * requests arriving meanwhile collapse into a single follow-up run, which
     * still sees whatever changed during the first. [GitPanelState.busy] gates
     * the UI's own actions, not this, because a refresh triggered by an
     * external write (the terminal, Claude Code) has to land even while a
     * commit is in flight.
     */
    fun refresh() {
        if (refreshJob?.isActive == true) {
            refreshAgain = true
            return
        }
        refreshJob = scope.launch {
            do {
                refreshAgain = false
                refreshOnce()
            } while (refreshAgain)
        }
    }

    private suspend fun refreshOnce() {
        if (!gitService.isRepository(projectRoot)) {
            _state.update { it.copy(isRepository = false, status = null, commits = emptyList()) }
            return
        }
        when (val result = gitService.status(projectRoot)) {
            is GitResult.Success -> {
                val commits = gitService.log(projectRoot).valueOrNull().orEmpty()
                _state.update {
                    it.copy(isRepository = true, status = result.value, commits = commits, error = null)
                }
            }
            is GitResult.Failure ->
                _state.update { it.copy(isRepository = true, error = result.message) }
            GitResult.NotARepository ->
                _state.update { it.copy(isRepository = false, status = null) }
        }
    }

    fun onMessageChanged(message: String) = _state.update { it.copy(commitMessage = message) }

    fun stage(paths: Collection<String>) = action { gitService.stage(it, paths) }

    fun unstage(paths: Collection<String>) = action { gitService.unstage(it, paths) }

    fun discard(paths: Collection<String>) = action { gitService.discard(it, paths) }

    fun initRepository() = action { gitService.createRepository(it) }

    /**
     * Commits, then clears the message only on success - a failed commit that
     * silently ate the message the user typed is the worst possible outcome.
     */
    fun commit() {
        val message = _state.value.commitMessage
        if (message.isBlank()) return
        action(onSuccess = { _state.update { it.copy(commitMessage = "") } }) { root ->
            gitService.commit(root, message, GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL)
        }
    }

    /** Closes the cached repository handle; call when the workspace goes away. */
    fun release() = gitService.release(projectRoot)

    private fun action(
        onSuccess: () -> Unit = {},
        block: suspend (File) -> GitResult<GitStatus>,
    ) {
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val result = block(projectRoot)) {
                is GitResult.Success -> {
                    onSuccess()
                    val commits = gitService.log(projectRoot).valueOrNull().orEmpty()
                    _state.update {
                        it.copy(isRepository = true, status = result.value, commits = commits, busy = false)
                    }
                }
                is GitResult.Failure ->
                    _state.update { it.copy(busy = false, error = result.message) }
                GitResult.NotARepository ->
                    _state.update { it.copy(busy = false, isRepository = false, status = null) }
            }
        }
    }

    private companion object {
        // Placeholder identity until a git settings screen exists; a commit
        // must have an author, and refusing to commit would be worse.
        const val GIT_AUTHOR_NAME = "easyIDE"
        const val GIT_AUTHOR_EMAIL = "dev@easyide.local"
    }
}
