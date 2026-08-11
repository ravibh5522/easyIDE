package dev.easyide.app.ui.screens.workspace

import dev.easyide.sandbox.git.GitCommit
import dev.easyide.sandbox.git.GitStatus

/**
 * Everything the source-control panel renders.
 *
 * `status == null` while the first read is in flight, which the panel shows as
 * empty rather than as "no changes" - the two look identical otherwise, and
 * claiming a clean tree before checking would be a lie the user acts on.
 */
data class GitPanelState(
    val isRepository: Boolean = false,
    val status: GitStatus? = null,
    val commits: List<GitCommit> = emptyList(),
    val commitMessage: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

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
)
