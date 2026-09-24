package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.diff.CommitSource
import dev.easyide.app.ui.shell.diff.Comparison
import dev.easyide.app.ui.shell.diff.DiffOutcome
import dev.easyide.app.ui.shell.diff.DiffProvider
import dev.easyide.app.ui.shell.diff.DiffSubject
import dev.easyide.app.ui.shell.diff.HunkAction
import dev.easyide.app.ui.shell.diff.HunkActions
import dev.easyide.sandbox.git.DiffHunk
import dev.easyide.sandbox.git.GitCommitDetail
import dev.easyide.sandbox.git.GitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The built-in `git-diff` provider and the commit source: both read the project's repository through
 * the git service. Hunks can be moved between the working tree and the index only where that is what
 * the comparison is (unstaged changes can be staged or discarded, staged ones unstaged); any other
 * pair of ends is a read-only view.
 */
class NativeGitDiffProvider(private val ctx: GitContext, private val hunks: GitDiffController) : DiffProvider, CommitSource {
    override val scheme = "git-diff"

    override fun subject(uri: DocumentUri): DiffSubject? =
        Comparison.of(uri)?.subject

    override suspend fun load(uri: DocumentUri): DiffOutcome {
        val c = Comparison.of(uri) ?: return DiffOutcome.Failed(uri.toString())
        return when (val result = ctx.service.compare(ctx.root, c.path, c.base, c.head)) {
            is GitResult.Success -> DiffOutcome.Ready(result.value)
            is GitResult.Failure -> DiffOutcome.Failed(result.message)
            GitResult.NotARepository -> DiffOutcome.Gone
        }
    }

    override fun actions(uri: DocumentUri): HunkActions? {
        val c = Comparison.of(uri) ?: return null
        return when {
            c.isUnstaged -> Actions(c, listOf(HunkAction.STAGE, HunkAction.DISCARD))
            c.isStaged -> Actions(c, listOf(HunkAction.UNSTAGE))
            else -> null
        }
    }

    override suspend fun detail(rev: String): GitResult<GitCommitDetail> = ctx.service.commitDetail(ctx.root, rev)

    private inner class Actions(private val c: Comparison, override val available: List<HunkAction>) : HunkActions {
        override val busy: Flow<Boolean> = ctx.state.map { it.busy }.distinctUntilChanged()

        override fun perform(action: HunkAction, hunk: DiffHunk) = when (action) {
            HunkAction.STAGE -> hunks.stageHunk(c.path, hunk)
            HunkAction.UNSTAGE -> hunks.unstageHunk(c.path, hunk)
            HunkAction.DISCARD -> hunks.requestDiscardHunk(c.path, hunk)
        }
    }
}
