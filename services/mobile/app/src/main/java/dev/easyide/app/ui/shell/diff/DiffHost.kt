package dev.easyide.app.ui.shell.diff

import dev.easyide.sandbox.git.GitCommitDetail
import dev.easyide.sandbox.git.GitResult
import kotlinx.coroutines.flow.StateFlow

/** Where the `git-commit` document reads a commit from. */
fun interface CommitSource {
    suspend fun detail(rev: String): GitResult<GitCommitDetail>
}

/**
 * Everything the git documents read: the providers of the diff document, the commit source, and a
 * [revision] that changes whenever the repository publishes a new status, so an open diff re-reads
 * instead of showing what it saw first.
 */
class DiffHost(val providers: DiffProviders, val commits: CommitSource, val revision: StateFlow<Long>)
