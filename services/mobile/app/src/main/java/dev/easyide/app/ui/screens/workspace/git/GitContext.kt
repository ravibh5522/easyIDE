package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.app.ui.screens.workspace.GitPanelState
import dev.easyide.sandbox.git.GitResult
import dev.easyide.sandbox.git.GitService
import dev.easyide.sandbox.git.GitStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * What every git controller shares: the one panel state, the service, and the
 * two moves they all make - run a user action against the repository, and
 * publish a fresh status. Kept here so the controllers differ only in what they
 * do, not in how a result reaches the screen.
 */
class GitContext(
    val state: MutableStateFlow<GitPanelState>,
    val service: GitService,
    val root: File,
    val scope: CoroutineScope,
) {
    /**
     * Called after every [publish]: whatever else shows repository content (the
     * open diff) re-reads, so it cannot go stale behind a stage or a refresh.
     */
    var onPublished: () -> Unit = {}

    /**
     * Runs [block] as a user action: [GitPanelState.busy] while it runs, the
     * error shown if it fails, the panel refreshed if it succeeds. [onFailure]
     * gets the first look at a failure and returns true when it handled it
     * (for example by asking a follow-up question instead of showing an error).
     * [after] runs once the fresh status is published, for a step that reads it (a push after a commit).
     */
    fun act(
        onSuccess: () -> Unit = {},
        onFailure: (GitResult.Failure) -> Boolean = { false },
        after: () -> Unit = {},
        block: suspend (File) -> GitResult<GitStatus>,
    ) {
        scope.launch {
            state.update { it.copy(busy = true, error = null, identityRequired = false) }
            when (val result = block(root)) {
                is GitResult.Success -> {
                    onSuccess()
                    publish(result.value)
                    after()
                }
                is GitResult.Failure ->
                    state.update { it.copy(busy = false, error = if (onFailure(result)) null else result.message) }
                GitResult.NotARepository ->
                    state.update { it.copy(busy = false, isRepository = false, status = null) }
            }
        }
    }

    /**
     * Stores [status] together with everything else that can change alongside
     * it - history, remotes, stashes - so a panel never shows a new status
     * next to an old commit list.
     */
    suspend fun publish(status: GitStatus, clearBusy: Boolean = true) {
        val commits = service.log(root).valueOrNull().orEmpty()
        val remotes = service.remotes(root).valueOrNull()
        val stashes = service.stashes(root).valueOrNull()
        val refs = service.refsByCommit(root).valueOrNull()
        val branches = service.branches(root).valueOrNull()
        state.update {
            it.copy(
                isRepository = true,
                status = status,
                commits = commits,
                remotes = remotes ?: it.remotes,
                stashes = stashes ?: it.stashes,
                refs = refs ?: it.refs,
                branches = branches ?: it.branches,
                busy = if (clearBusy) false else it.busy,
            )
        }
        onPublished()
    }
}
