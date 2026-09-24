package dev.easyide.app.ui.screens.workspace.git

/** Commit-box actions that are not part of the original callbacks: amend, identity, confirmations. */
interface GitCommitActions {
    fun setAmend(amend: Boolean)

    /** Saves the identity typed into the inline form, then carries on with the commit that asked for it. */
    fun saveIdentity(name: String, email: String, projectOnly: Boolean)

    fun dismissIdentityPrompt()

    /** The user's answer to [GitPanelState.confirm][dev.easyide.app.ui.screens.workspace.GitPanelState.confirm]. */
    fun answerConfirm(accepted: Boolean)
}

/**
 * The action surfaces of the source-control pane, one per concern. The pane
 * takes this whole rather than a lambda per button: there are dozens, they
 * group naturally, and a new action should not touch every call site.
 */
class GitControllers(
    val remote: GitRemoteController,
    val branches: GitBranchController,
    val diff: GitDiffController,
    val commit: GitCommitActions,
)
