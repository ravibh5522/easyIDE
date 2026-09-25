package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.sandbox.git.GitIdentity
import dev.easyide.sandbox.git.GitResult
import dev.easyide.sandbox.git.GitStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

/**
 * What the graph's commit menu does to a commit: checkout detached, branch or tag at it, cherry-pick it and
 * compare it with another ref. Checkout, branch creation and deletion go through [GitBranchController], so
 * the dirty-tree question and the unmerged-branch warning are the ones the branch sheet already asks.
 */
class GitHistoryController(
    private val ctx: GitContext,
    private val branches: GitBranchController,
    private val settings: GitSettingsPort,
) {
    fun checkoutDetached(sha: String) = ctx.act { root -> ctx.service.checkoutDetached(root, sha) }

    fun createBranchAt(sha: String, name: String) = branches.create(name, sha)

    /** An annotated tag needs a tagger, so the configured identity is required; a lightweight one would not, but one rule is simpler to explain. */
    fun createTag(sha: String, name: String, message: String) = withIdentity { identity ->
        ctx.act { root -> tagged(root, sha, name.trim(), message.trim().ifEmpty { null }, identity) }
    }

    private suspend fun tagged(root: java.io.File, sha: String, name: String, message: String?, identity: GitIdentity): GitResult<GitStatus> =
        when (val made = ctx.service.createTag(root, name, sha, message, identity.name, identity.email)) {
            is GitResult.Success -> ctx.service.status(root)
            is GitResult.Failure -> GitResult.Failure(made.message, made.kind)
            GitResult.NotARepository -> GitResult.NotARepository
        }

    fun cherryPick(sha: String) = withIdentity { identity ->
        ctx.act { root -> ctx.service.cherryPick(root, sha, identity.name, identity.email) }
    }

    /** [sha] against the tip of its branch's upstream; nothing to compare when the branch tracks nothing. */
    fun compareWithRemote(sha: String, label: String) {
        val upstream = ctx.state.value.upstream ?: return
        compare(head = upstream, headLabel = upstream, base = sha, baseLabel = label)
    }

    /** [sha] against the point where its history and HEAD's part ways. */
    fun compareWithMergeBase(sha: String, label: String) {
        ctx.scope.launch {
            when (val base = ctx.service.mergeBase(ctx.root, sha, HEAD)) {
                is GitResult.Success -> base.value?.let { compare(head = sha, headLabel = label, base = it, baseLabel = it.take(SHORT)) }
                is GitResult.Failure -> ctx.state.update { it.copy(error = base.message) }
                GitResult.NotARepository -> Unit
            }
        }
    }

    /** What changed going from [base] to [head]: the files, each opening its own diff. */
    fun compare(head: String, headLabel: String, base: String, baseLabel: String) {
        ctx.scope.launch {
            when (val files = ctx.service.changedBetween(ctx.root, base, head)) {
                is GitResult.Success -> ctx.state.update { it.copy(comparison = GitComparison(baseLabel, headLabel, base, head, files.value)) }
                is GitResult.Failure -> ctx.state.update { it.copy(error = files.message) }
                GitResult.NotARepository -> Unit
            }
        }
    }

    fun closeComparison() = ctx.state.update { it.copy(comparison = null) }

    private fun withIdentity(block: (GitIdentity) -> Unit) {
        ctx.scope.launch {
            val identity = settings.current().identity
            if (identity == null) ctx.state.update { it.copy(identityRequired = true) } else block(identity)
        }
    }

    private companion object {
        const val HEAD = "HEAD"
        const val SHORT = 8
    }
}
