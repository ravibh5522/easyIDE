package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.app.ui.kit.KitProgress
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.screens.workspace.GitPanelState
import dev.easyide.app.ui.screens.workspace.PanelTitleRow
import dev.easyide.app.ui.screens.workspace.SourceControlCallbacks
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.tabular
import dev.easyide.sandbox.git.GitRepoState
import dev.easyide.sandbox.git.GitStatus

/**
 * The pane's header: the title row with the list/tree toggle, refresh and the more-actions menu, then one compact row for the
 * branch (tap switches it) with how far it is from its upstream and the pull and push buttons at
 * its end; without a remote that row offers to add one instead.
 */
@Composable
internal fun ScmHeader(state: GitPanelState, callbacks: SourceControlCallbacks, treeView: Boolean, onToggleTree: () -> Unit) {
    val status = state.status
    val ready = state.isRepository && status != null
    val git = callbacks.git
    PanelTitleRow(stringResource(R.string.git_title)) {
        if (ready) {
            KitIconButton(
                if (treeView) Icons.Filled.ViewList else Icons.Filled.AccountTree,
                stringResource(if (treeView) R.string.gitui_view_as_list else R.string.gitui_view_as_tree),
                onToggleTree,
            )
        }
        KitIconButton(Icons.Filled.Refresh, stringResource(R.string.git_refresh), callbacks.onRefresh, enabled = !state.busy)
        if (ready) MoreActions(state, status, git)
    }

    if (ready) {
        BranchRow(state, status, git)
        RepoStateBanner(status.state)
    }

    // Reserved height either way, so the list does not jump when work starts.
    Box(Modifier.fillMaxWidth().height(Kit.space.xs)) {
        if (state.busy || state.operation?.status == OperationStatus.RUNNING) KitProgress(fraction = null)
    }
}

/** The branch is a row: tap switches, the counts say how far it is from its upstream, the buttons move commits. */
@Composable
private fun BranchRow(state: GitPanelState, status: GitStatus, git: GitControllers) {
    if (state.remotes.isEmpty()) {
        KitRow(
            title = stringResource(R.string.git_remote_add),
            subtitle = stringResource(R.string.git_no_remote),
            onClick = git.branches::openRemotes,
            leading = { Image(Icons.Filled.Add, null, Modifier.size(Kit.control.rowIcon), colorFilter = ColorFilter.tint(Kit.colors.accent)) },
        )
        return
    }
    val idle = !state.busy && state.operation?.status != OperationStatus.RUNNING
    val publish = status.upstream == null
    KitRow(
        title = status.branch,
        onClick = git.branches::openBranches,
        leading = {
            Image(
                Icons.Filled.CallSplit,
                stringResource(R.string.git_branch_chip_cd, status.branch),
                Modifier.size(Kit.control.rowIcon),
                colorFilter = ColorFilter.tint(Kit.colors.textMuted),
            )
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!publish) Counts(status.ahead, status.behind)
                KitIconButton(
                    Icons.Filled.ArrowDownward, countLabel(R.string.git_pull, status.behind), git.remote::pull,
                    enabled = idle && !publish && !status.detached,
                )
                KitIconButton(
                    Icons.Filled.ArrowUpward, countLabel(if (publish) R.string.git_publish else R.string.git_push, status.ahead), git.remote::push,
                    enabled = idle && GitRemotePlanner.pushOp(status, state.remotes) != null,
                )
            }
        },
    )
}

/** Fetch, remotes, stashes and force push: what does not earn a button in the branch row. */
@Composable
private fun MoreActions(state: GitPanelState, status: GitStatus, git: GitControllers) {
    val idle = !state.busy && state.operation?.status != OperationStatus.RUNNING
    val canPush = idle && GitRemotePlanner.pushOp(status, state.remotes) != null
    var menu by remember { mutableStateOf(false) }
    Box {
        KitIconButton(Icons.Filled.MoreVert, stringResource(R.string.git_more_actions), { menu = true })
        KitMenu(
            expanded = menu,
            onDismiss = { menu = false },
            items = listOf(
                KitMenuItem.Action(stringResource(R.string.git_fetch), git.remote::fetch, enabled = idle && state.remotes.isNotEmpty()),
                KitMenuItem.Action(stringResource(R.string.git_remotes_title), git.branches::openRemotes),
                KitMenuItem.Action(stringResource(R.string.git_stashes_menu, state.stashes.size), git.branches::openStashes),
                KitMenuItem.Action(stringResource(R.string.git_force_push), git.remote::requestForcePush, enabled = canPush && status.upstream != null, danger = true),
            ),
        )
    }
}

/** Ahead and behind as arrow + number; hidden when both are zero (in sync). */
@Composable
private fun Counts(ahead: Int, behind: Int) {
    val aheadCd = pluralStringResource(R.plurals.git_ahead_cd, ahead, ahead)
    val behindCd = pluralStringResource(R.plurals.git_behind_cd, behind, behind)
    Row(horizontalArrangement = Arrangement.spacedBy(Kit.space.s), verticalAlignment = Alignment.CenterVertically) {
        if (ahead > 0) Count(Icons.Filled.ArrowUpward, aheadCd, ahead)
        if (behind > 0) Count(Icons.Filled.ArrowDownward, behindCd, behind)
    }
}

@Composable
private fun Count(icon: ImageVector, description: String, n: Int) {
    val colors = Kit.colors
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        Image(icon, null, Modifier.size(IconSize.xs), colorFilter = ColorFilter.tint(colors.textMuted))
        BasicText("$n", style = Kit.text.monoSmall.copy(color = colors.textMuted).tabular())
    }
}

/** "Pull" or, with something to move, "Pull 2": the count is part of the label so it is read, not just seen. */
@Composable
private fun countLabel(label: Int, count: Int): String =
    if (count > 0) stringResource(R.string.wp_git_sync_count, stringResource(label), count) else stringResource(label)

@Composable
private fun RepoStateBanner(state: GitRepoState) {
    val res = when (state) {
        GitRepoState.NORMAL -> return
        GitRepoState.MERGING -> R.string.git_state_merging
        GitRepoState.REBASING -> R.string.git_state_rebasing
        GitRepoState.OTHER -> R.string.git_state_other
    }
    KitBanner(stringResource(res), tone = Tone.Warning)
}
