package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.sandbox.git.DiffHunk
import dev.easyide.sandbox.git.DiffSource
import dev.easyide.sandbox.git.GitResult
import dev.easyide.sandbox.git.GitStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The diff screen's state and hunk actions. Diffs are computed by JGit on the
 * service's IO dispatcher; this controller only ever holds the parsed result.
 *
 * A diff is re-read whenever the repository publishes a new status (see
 * [GitContext.onPublished]), so staging a hunk, editing the file in the editor,
 * or a terminal write all leave the screen showing the truth, not a snapshot.
 */
class GitDiffController(private val ctx: GitContext) {

    init {
        ctx.onPublished = ::reload
    }

    private var loadJob: Job? = null

    /** Opens [path]'s diff on [source]; a hunk action's own follow-up reload keeps the same source. */
    fun open(path: String, source: DiffSource) {
        ctx.state.update { it.copy(diff = GitDiffState(path, source)) }
        reload()
    }

    /** Switches between the staged and unstaged views of the open file. */
    fun showSource(source: DiffSource) {
        val current = ctx.state.value.diff ?: return
        if (current.source == source) return
        ctx.state.update { it.copy(diff = GitDiffState(current.path, source)) }
        reload()
    }

    fun close() = ctx.state.update { it.copy(diff = null) }

    /**
     * Re-reads the open diff. Latest wins: a slow read for a superseded
     * request must not overwrite a newer one.
     */
    fun reload() {
        val open = ctx.state.value.diff ?: return
        loadJob?.cancel()
        loadJob = ctx.scope.launch {
            val result = ctx.service.fileDiff(ctx.root, open.path, open.source)
            ctx.state.update { s ->
                val now = s.diff
                if (now == null || now.path != open.path || now.source != open.source) return@update s
                when (result) {
                    is GitResult.Success -> s.copy(diff = now.copy(diff = result.value, error = null))
                    is GitResult.Failure -> s.copy(diff = now.copy(diff = null, error = result.message))
                    GitResult.NotARepository -> s.copy(diff = null)
                }
            }
        }
    }

    fun stageHunk(hunk: DiffHunk) = hunkAct { root, path -> ctx.service.stageHunk(root, path, hunk) }

    fun unstageHunk(hunk: DiffHunk) = hunkAct { root, path -> ctx.service.unstageHunk(root, path, hunk) }

    fun requestDiscardHunk(hunk: DiffHunk) {
        val path = ctx.state.value.diff?.path ?: return
        ctx.state.update { it.copy(confirm = GitConfirm.DiscardHunk(path, hunk)) }
    }

    fun confirmed(confirm: GitConfirm): Boolean {
        if (confirm !is GitConfirm.DiscardHunk) return false
        ctx.act { root -> ctx.service.discardHunk(root, confirm.path, confirm.hunk) }
        return true
    }

    private fun hunkAct(block: suspend (java.io.File, String) -> GitResult<GitStatus>) {
        val path = ctx.state.value.diff?.path ?: return
        ctx.act { root -> block(root, path) }
    }
}
