package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
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
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
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

/** The pane's title row, branch chip and remote actions. */
@Composable
internal fun ScmHeader(state: GitPanelState, callbacks: SourceControlCallbacks) {
    PanelTitleRow(stringResource(R.string.git_title)) {
        KitIconButton(Icons.Filled.Refresh, stringResource(R.string.git_refresh), callbacks.onRefresh, enabled = !state.busy)
    }

    val status = state.status
    if (state.isRepository && status != null) {
        BranchChip(status, onClick = callbacks.git.branches::openBranches)
        if (state.remotes.isEmpty()) NoRemote(callbacks.git.branches::openRemotes) else SyncBar(state, status, callbacks.git)
        RepoStateBanner(status.state)
    }

    // Reserved height either way, so the list does not jump when work starts.
    Box(Modifier.fillMaxWidth().height(Kit.space.s)) {
        if (state.busy || state.operation?.status == OperationStatus.RUNNING) KitProgress(fraction = null)
    }
}

/** The branch is a row: tap switches, the counts say how far it is from its upstream. */
@Composable
private fun BranchChip(status: GitStatus, onClick: () -> Unit) {
    KitRow(
        title = status.branch,
        mono = true,
        onClick = onClick,
        leading = {
            Image(
                Icons.Filled.AccountTree,
                stringResource(R.string.git_branch_chip_cd, status.branch),
                Modifier.size(IconSize.m),
                colorFilter = ColorFilter.tint(Kit.colors.textMuted),
            )
        },
        trailing = { if (status.upstream != null) Counts(status.ahead, status.behind) },
    )
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

@Composable
private fun NoRemote(onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Kit.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = stringResource(R.string.git_no_remote),
            style = Kit.text.label.copy(color = Kit.colors.textMuted),
            modifier = Modifier.weight(1f),
        )
        KitButton(stringResource(R.string.git_remote_add), onAdd, style = KitButtonStyle.Ghost)
    }
}

@Composable
private fun SyncBar(state: GitPanelState, status: GitStatus, git: GitControllers) {
    val idle = !state.busy && state.operation?.status != OperationStatus.RUNNING
    val canPush = idle && GitRemotePlanner.pushOp(status, state.remotes) != null
    val publish = status.upstream == null
    var menu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Kit.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SyncAction(Icons.Filled.Sync, stringResource(R.string.git_fetch), idle, git.remote::fetch)
        SyncAction(
            Icons.Filled.ArrowDownward, countLabel(R.string.git_pull, status.behind),
            enabled = idle && status.upstream != null && !status.detached,
            onClick = git.remote::pull,
        )
        SyncAction(
            Icons.Filled.ArrowUpward, countLabel(if (publish) R.string.git_publish else R.string.git_push, status.ahead),
            enabled = canPush,
            onClick = git.remote::push,
        )
        Box {
            KitIconButton(Icons.Filled.MoreVert, stringResource(R.string.git_more_actions), { menu = true })
            KitMenu(
                expanded = menu,
                onDismiss = { menu = false },
                items = listOf(
                    KitMenuItem.Action(stringResource(R.string.git_remotes_title), git.branches::openRemotes),
                    KitMenuItem.Action(stringResource(R.string.git_stashes_menu, state.stashes.size), git.branches::openStashes),
                    KitMenuItem.Action(stringResource(R.string.git_force_push), git.remote::requestForcePush, enabled = canPush && !publish, danger = true),
                ),
            )
        }
    }
}

/** "Pull" or, with something to move, "Pull 2": the count is part of the label so it is read, not just seen. */
@Composable
private fun countLabel(label: Int, count: Int): String =
    if (count > 0) stringResource(R.string.wp_git_sync_count, stringResource(label), count) else stringResource(label)

@Composable
private fun RowScope.SyncAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    KitButton(label, onClick, Modifier.weight(1f), KitButtonStyle.Ghost, icon = icon, enabled = enabled)
}

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
