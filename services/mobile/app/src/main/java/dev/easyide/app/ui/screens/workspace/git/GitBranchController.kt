package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.sandbox.git.GitFailureKind
import dev.easyide.sandbox.git.GitResult
import dev.easyide.sandbox.git.GitStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

/**
 * Branch and stash operations. Both answer the same question from different
 * sides - "what do I do with my current edits when I move" - so the dirty-tree
 * handling lives here once: switching with edits offers to stash them, and
 * nothing is ever discarded to make a switch succeed.
 */
class GitBranchController(private val ctx: GitContext) {

    fun openBranches() {
        ctx.state.update { it.copy(sheet = GitSheet.BRANCHES) }
        reloadBranches()
    }

    fun openStashes() = ctx.state.update { it.copy(sheet = GitSheet.STASHES) }

    fun openRemotes() = ctx.state.update { it.copy(sheet = GitSheet.REMOTES) }

    fun closeSheet() = ctx.state.update { it.copy(sheet = null) }

    private fun reloadBranches() {
        ctx.scope.launch {
            ctx.service.branches(ctx.root).valueOrNull()?.let { list -> ctx.state.update { it.copy(branches = list) } }
        }
    }

    /**
     * [name] is a local branch or a remote-tracking one (`origin/feature`, which
     * creates and tracks a local copy). With tracked edits present the user
     * chooses first.
     */
    fun switchTo(name: String) {
        val status = ctx.state.value.status ?: return
        if (status.hasTrackedChanges) {
            ctx.state.update { it.copy(confirm = GitConfirm.SwitchDirty(name)) }
        } else {
            doSwitch(name)
        }
    }

    private fun doSwitch(name: String) = branchAct(closeSheet = true) { root -> ctx.service.switchBranch(root, name) }

    /** Shelves the edits, then switches; the stash stays in the list to pop later. */
    private fun stashThenSwitch(name: String) = branchAct(closeSheet = true) { root ->
        when (val stashed = ctx.service.stash(root, message = null, includeUntracked = false)) {
            is GitResult.Success -> ctx.service.switchBranch(root, name)
            else -> stashed
        }
    }

    /** From HEAD, or from [startPoint] (a branch name or a remote-tracking one). */
    fun create(name: String, startPoint: String?) = branchAct(closeSheet = true) { root ->
        ctx.service.createBranch(root, name.trim(), startPoint, checkout = true)
    }

    fun rename(oldName: String, newName: String) = branchAct { root ->
        ctx.service.renameBranch(root, oldName, newName.trim())
    }

    fun requestDelete(name: String) =
        ctx.state.update { it.copy(confirm = GitConfirm.DeleteBranch(name, unmerged = false)) }

    private fun delete(name: String, force: Boolean) = branchAct(
        onFailure = { failure ->
            // Git refused the safe delete: ask again, now naming the risk.
            val unmerged = failure.kind == GitFailureKind.UNMERGED_BRANCH
            if (unmerged) ctx.state.update { it.copy(confirm = GitConfirm.DeleteBranch(name, unmerged = true)) }
            unmerged
        },
    ) { root -> ctx.service.deleteBranch(root, name, force) }

    /** A branch action: on success the list is re-read; a failure (git can still refuse) is shown as an error. */
    private fun branchAct(
        closeSheet: Boolean = false,
        onFailure: (GitResult.Failure) -> Boolean = { false },
        block: suspend (java.io.File) -> GitResult<GitStatus>,
    ) = ctx.act(
        onSuccess = {
            if (closeSheet) ctx.state.update { it.copy(sheet = null) }
            reloadBranches()
        },
        onFailure = onFailure,
        block = block,
    )

    // ---- stash -----------------------------------------------------------

    fun stash(message: String?, includeUntracked: Boolean) =
        ctx.act { root -> ctx.service.stash(root, message, includeUntracked) }

    fun popStash(index: Int) = ctx.act { root -> ctx.service.stashPop(root, index) }

    fun applyStash(index: Int) = ctx.act { root -> ctx.service.stashApply(root, index) }

    fun requestDropStash(index: Int) = ctx.state.update { it.copy(confirm = GitConfirm.DropStash(index)) }

    /** Runs the branch/stash half of a confirmation the user accepted; true if it was one of ours. */
    fun confirmed(confirm: GitConfirm): Boolean {
        when (confirm) {
            is GitConfirm.DeleteBranch -> delete(confirm.name, force = confirm.unmerged)
            is GitConfirm.SwitchDirty -> stashThenSwitch(confirm.target)
            is GitConfirm.DropStash -> ctx.act { root -> ctx.service.stashDrop(root, confirm.index) }
            else -> return false
        }
        return true
    }
}
