package dev.easyide.app.ui.screens.workspace

import dev.easyide.app.ui.screens.workspace.git.GitBranchController
import dev.easyide.app.ui.screens.workspace.git.GitCommitActions
import dev.easyide.app.ui.screens.workspace.git.GitConfirm
import dev.easyide.app.ui.screens.workspace.git.GitContext
import dev.easyide.app.ui.screens.workspace.git.GitControllers
import dev.easyide.app.ui.screens.workspace.git.GitDefaults
import dev.easyide.app.ui.screens.workspace.git.GitDiffController
import dev.easyide.app.ui.screens.workspace.git.GitNetworkRunner
import dev.easyide.app.ui.screens.workspace.git.GitRemoteController
import dev.easyide.app.ui.screens.workspace.git.GitSettingsPort
import dev.easyide.app.ui.screens.workspace.git.GitTokenSink
import dev.easyide.app.ui.screens.workspace.git.current
import dev.easyide.sandbox.git.GitIdentity
import dev.easyide.sandbox.git.GitResult
import dev.easyide.sandbox.git.GitService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 *
 * This class owns status, staging and committing; network, branch/stash and
 * diff behaviour live in the controllers exposed as [controllers], all writing
 * the one [state].
 */
class WorkspaceGitController(
    private val gitService: GitService,
    private val projectRoot: File,
    private val scope: CoroutineScope,
    network: GitNetworkRunner,
    private val settings: GitSettingsPort,
    tokens: GitTokenSink,
    foreground: StateFlow<Boolean>,
) : GitCommitActions {
    private val _state = MutableStateFlow(GitPanelState())
    val state: StateFlow<GitPanelState> = _state.asStateFlow()

    private val ctx = GitContext(_state, gitService, projectRoot, scope)
    private val diffs = GitDiffController(ctx)

    val controllers = GitControllers(
        remote = GitRemoteController(ctx, network, settings, tokens, foreground, refresh = ::refresh),
        branches = GitBranchController(ctx),
        diff = diffs,
        commit = this,
    )

    private var refreshJob: Job? = null
    private var refreshAgain = false
    private var draftJob: Job? = null
    private var draftLoaded = false

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
                loadDraftOnce()
                ctx.publish(result.value, clearBusy = false)
                _state.update { it.copy(error = null) }
            }
            is GitResult.Failure ->
                _state.update { it.copy(isRepository = true, error = result.message) }
            GitResult.NotARepository ->
                _state.update { it.copy(isRepository = false, status = null) }
        }
    }

    /** The saved draft fills an empty box once; later refreshes must not overwrite what is being typed. */
    private suspend fun loadDraftOnce() {
        if (draftLoaded) return
        draftLoaded = true
        val draft = gitService.readDraft(projectRoot)
        if (draft.isNotEmpty()) _state.update { if (it.commitMessage.isEmpty()) it.copy(commitMessage = draft) else it }
    }

    fun onMessageChanged(message: String) {
        _state.update { it.copy(commitMessage = message) }
        draftJob?.cancel()
        draftJob = scope.launch {
            delay(GitDefaults.DRAFT_SAVE_DELAY_MS)
            gitService.writeDraft(projectRoot, message)
        }
    }

    fun stage(paths: Collection<String>) = ctx.act { gitService.stage(it, paths) }

    fun unstage(paths: Collection<String>) = ctx.act { gitService.unstage(it, paths) }

    /** Discarding loses work with no way back, so it is always asked about first. */
    fun discard(paths: Collection<String>) {
        if (paths.isEmpty()) return
        _state.update { it.copy(confirm = GitConfirm.DiscardFiles(paths.toList())) }
    }

    fun initRepository() = ctx.act { gitService.createRepository(it) }

    // ---- commit ---------------------------------------------------------------

    override fun setAmend(amend: Boolean) {
        if (amend == _state.value.amend) return
        scope.launch {
            val head = gitService.log(projectRoot, 1).valueOrNull()?.firstOrNull()?.body?.trim().orEmpty()
            _state.update { s ->
                val message = when {
                    amend && s.commitMessage.isBlank() -> head
                    !amend && s.commitMessage == head -> ""
                    else -> s.commitMessage
                }
                s.copy(amend = amend, commitMessage = message)
            }
        }
    }

    /**
     * Commits, then clears the message only on success - a failed commit that
     * silently ate the message the user typed is the worst possible outcome.
     *
     * With no author identity configured nothing is committed: the inline form
     * opens instead, and [saveIdentity] resumes here. A made-up identity would
     * end up in history, where it cannot be taken back.
     */
    fun commit() {
        val state = _state.value
        if (state.commitMessage.isBlank()) return
        scope.launch {
            val identity = settings.current().identity
            when {
                identity == null -> _state.update { it.copy(identityPrompt = true) }
                state.amend && amendRewritesPushed() -> _state.update { it.copy(confirm = GitConfirm.AmendPushed) }
                else -> commitAs(identity)
            }
        }
    }

    private suspend fun amendRewritesPushed(): Boolean = gitService.isHeadPushed(projectRoot).valueOrNull() == true

    private fun commitAs(identity: GitIdentity) {
        val state = _state.value
        val message = state.commitMessage
        val amend = state.amend
        ctx.act(
            onSuccess = {
                draftJob?.cancel()
                _state.update { it.copy(commitMessage = "", amend = false, identityPrompt = false) }
                scope.launch { gitService.writeDraft(projectRoot, "") }
            },
        ) { root -> gitService.commit(root, message, identity.name, identity.email, amend) }
    }

    override fun saveIdentity(name: String, email: String, projectOnly: Boolean) {
        val identity = GitIdentity.of(name, email) ?: return
        scope.launch {
            if (!settings.saveIdentity(identity, projectOnly)) {
                _state.update { it.copy(identitySaveFailed = true) }
                return@launch
            }
            _state.update { it.copy(identityPrompt = false, identitySaveFailed = false) }
            commitAs(identity)
        }
    }

    override fun dismissIdentityPrompt() = _state.update { it.copy(identityPrompt = false, identitySaveFailed = false) }

    // ---- confirmations --------------------------------------------------------

    override fun answerConfirm(accepted: Boolean) {
        val confirm = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        if (!accepted) return
        when {
            controllers.branches.confirmed(confirm) -> Unit
            controllers.diff.confirmed(confirm) -> Unit
            confirm is GitConfirm.DiscardFiles -> ctx.act { gitService.discard(it, confirm.paths) }
            controllers.remote.confirmed(confirm) -> Unit
            confirm is GitConfirm.AmendPushed ->
                scope.launch { settings.current().identity?.let(::commitAs) }
        }
    }

    /** Closes the cached repository handle; call when the workspace goes away. */
    fun release() = gitService.release(projectRoot)
}
