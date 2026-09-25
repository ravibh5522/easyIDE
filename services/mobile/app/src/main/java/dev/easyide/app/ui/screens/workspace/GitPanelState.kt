package dev.easyide.app.ui.screens.workspace

import dev.easyide.app.ui.screens.workspace.git.GitConfirm
import dev.easyide.app.ui.screens.workspace.git.GitComparison
import dev.easyide.app.ui.screens.workspace.git.GitControllers
import dev.easyide.app.ui.screens.workspace.git.GitOperation
import dev.easyide.app.ui.screens.workspace.git.GitSheet
import dev.easyide.sandbox.git.GitBranch
import dev.easyide.sandbox.git.GitCommit
import dev.easyide.sandbox.git.GitRef
import dev.easyide.sandbox.git.GitRemoteInfo
import dev.easyide.sandbox.git.GitStashEntry
import dev.easyide.sandbox.git.GitStatus

/**
 * Everything the source-control panel renders.
 *
 * `status == null` while the first read is in flight, which the panel shows as
 * empty rather than as "no changes" - the two look identical otherwise, and
 * claiming a clean tree before checking would be a lie the user acts on.
 *
 * The status bar reads [GitStatus.branch] and [ahead] / [behind]; those names
 * are stable.
 */
data class GitPanelState(
    val isRepository: Boolean = false,
    val status: GitStatus? = null,
    val commits: List<GitCommit> = emptyList(),
    val commitMessage: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    /** The next commit replaces HEAD instead of adding to it. */
    val amend: Boolean = false,
    /** Set when a commit was attempted with no author identity configured; shows the inline form. */
    val identityPrompt: Boolean = false,
    /** The identity form's save was refused by the settings layer; the form stays open with a message. */
    val identitySaveFailed: Boolean = false,
    val remotes: List<GitRemoteInfo> = emptyList(),
    val stashes: List<GitStashEntry> = emptyList(),
    /** Loaded when the branch sheet opens and after every branch operation. */
    val branches: List<GitBranch> = emptyList(),
    /** Branch, remote-branch and tag names per commit id, for the chips of the graph and the commit menu. */
    val refs: Map<String, List<GitRef>> = emptyMap(),
    /** A cherry-pick or tag was asked for with no author identity configured; shown as a banner. */
    val identityRequired: Boolean = false,
    /** The open "compare" result, or null. */
    val comparison: GitComparison? = null,
    val sheet: GitSheet? = null,
    val confirm: GitConfirm? = null,
    val operation: GitOperation? = null,
) {
    /** Commits this branch has that its upstream lacks; 0 when there is no upstream. */
    val ahead: Int get() = status?.ahead ?: 0

    /** Commits the upstream has that this branch lacks; 0 when there is no upstream. */
    val behind: Int get() = status?.behind ?: 0

    /** `origin/main`-style name of the tracked branch, or null. */
    val upstream: String? get() = status?.upstream
}

/** Grouped so adding an action does not change every call site. */
data class SourceControlCallbacks(
    val onMessageChanged: (String) -> Unit,
    val onCommit: () -> Unit,
    val onStage: (Collection<String>) -> Unit,
    val onUnstage: (Collection<String>) -> Unit,
    val onDiscard: (Collection<String>) -> Unit,
    val onInitRepository: () -> Unit,
    val onRefresh: () -> Unit,
    val onOpenFile: (String) -> Unit,
    /** Remote, branch, stash and diff actions; each group owns its own controller. */
    val git: GitControllers,
)
