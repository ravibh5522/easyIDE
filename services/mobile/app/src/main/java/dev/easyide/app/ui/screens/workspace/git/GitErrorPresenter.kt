package dev.easyide.app.ui.screens.workspace.git

import androidx.annotation.StringRes
import dev.easyide.app.R
import dev.easyide.sandbox.git.GitFailureKind

/**
 * The plain-language line shown above git's own output when a failure has a
 * known cause and a known next step. Unknown failures show git's words alone:
 * a guess would be worse than the raw message.
 */
object GitErrorPresenter {

    @StringRes
    fun explanation(kind: GitFailureKind): Int? = when (kind) {
        GitFailureKind.AUTH -> R.string.git_error_auth
        GitFailureKind.NON_FAST_FORWARD -> R.string.git_error_non_fast_forward
        GitFailureKind.NO_UPSTREAM -> R.string.git_error_no_upstream
        GitFailureKind.DIVERGED -> R.string.git_error_diverged
        GitFailureKind.MERGE_CONFLICT -> R.string.git_error_merge_conflict
        GitFailureKind.DIRTY_TREE -> R.string.git_error_dirty_tree
        GitFailureKind.UNMERGED_BRANCH -> R.string.git_error_unmerged_branch
        GitFailureKind.NETWORK -> R.string.git_error_network
        GitFailureKind.UNKNOWN -> null
    }
}
