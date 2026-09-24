package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.sandbox.git.DiffHunk
import dev.easyide.sandbox.git.DiffSource
import dev.easyide.sandbox.git.FileDiff
import dev.easyide.sandbox.git.GitFailureKind

/** The list sheets the source-control pane can open over itself. */
enum class GitSheet { BRANCHES, REMOTES, STASHES }

/**
 * A question the user must answer before something destructive or surprising
 * happens. One field in the panel state, because two confirmations are never
 * pending at once; the pane renders whichever is set.
 */
sealed interface GitConfirm {
    data class DiscardFiles(val paths: List<String>) : GitConfirm
    data class DiscardHunk(val path: String, val hunk: DiffHunk) : GitConfirm

    /** [unmerged] is set on the second ask, after git refused the safe delete. */
    data class DeleteBranch(val name: String, val unmerged: Boolean) : GitConfirm

    /** The tree has edits a checkout could overwrite: stash them first, or stay. */
    data class SwitchDirty(val target: String) : GitConfirm
    data class DropStash(val index: Int) : GitConfirm
    data class RemoveRemote(val name: String) : GitConfirm

    /** Amending a commit that is already on a remote means the next push must be forced. */
    data object AmendPushed : GitConfirm

    /** [target] is the upstream (`origin/main`) or, before one exists, the branch name. */
    data class ForcePush(val target: String) : GitConfirm
}

enum class GitOperationKind { FETCH, PULL, PUSH, PUBLISH, FORCE_PUSH }

enum class OperationStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED }

/**
 * A network operation in flight or just finished, with git's streamed output.
 * [failure] and [authHost] let the pane offer the fix (enter a token for that
 * host) instead of only showing git's words.
 */
data class GitOperation(
    val kind: GitOperationKind,
    val status: OperationStatus = OperationStatus.RUNNING,
    val output: List<String> = emptyList(),
    val failure: GitFailureKind? = null,
    val authHost: String? = null,
)

/**
 * The diff screen. [diff] is null while it loads; [source] is which side of the
 * index is being compared, switchable when a file has both staged and unstaged
 * changes.
 */
data class GitDiffState(
    val path: String,
    val source: DiffSource,
    val diff: FileDiff? = null,
    val error: String? = null,
)

/** Upper bound on retained progress lines: a clone-sized fetch prints thousands, the panel shows the tail. */
const val MAX_OPERATION_LINES = 400
