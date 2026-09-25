package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.app.ui.shell.diff.DiffHost
import dev.easyide.app.ui.shell.diff.DiffProviders
import dev.easyide.sandbox.git.DiffHunk
import dev.easyide.sandbox.git.GitResult
import dev.easyide.sandbox.git.GitStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the git documents need from the repository: the [host] they read diffs and commits through,
 * and the hunk actions they apply. A diff is computed by JGit on the service's IO dispatcher and held
 * by the document that shows it, so several can be open at once; this controller holds none.
 *
 * Every published status bumps [DiffHost.revision] (see [GitContext.onPublished]), which makes an open
 * diff re-read: staging a hunk, editing the file in the editor or a terminal write all leave the
 * document showing the truth, not a snapshot.
 */
class GitDiffController(private val ctx: GitContext) {
    private val published = MutableStateFlow(0L)
    private val native = NativeGitDiffProvider(ctx, this)

    val host = DiffHost(DiffProviders.EMPTY.register(native), native, published.asStateFlow())

    init {
        ctx.onPublished = { published.update { it + 1 } }
    }

    val revision: StateFlow<Long> get() = host.revision

    fun stageHunk(path: String, hunk: DiffHunk) = hunkAct { root -> ctx.service.stageHunk(root, path, hunk) }

    fun unstageHunk(path: String, hunk: DiffHunk) = hunkAct { root -> ctx.service.unstageHunk(root, path, hunk) }

    fun requestDiscardHunk(path: String, hunk: DiffHunk) {
        ctx.state.update { it.copy(confirm = GitConfirm.DiscardHunk(path, hunk)) }
    }

    fun confirmed(confirm: GitConfirm): Boolean {
        if (confirm !is GitConfirm.DiscardHunk) return false
        hunkAct { root -> ctx.service.discardHunk(root, confirm.path, confirm.hunk) }
        return true
    }

    private fun hunkAct(block: suspend (java.io.File) -> GitResult<GitStatus>) = ctx.act { root -> block(root) }
}
