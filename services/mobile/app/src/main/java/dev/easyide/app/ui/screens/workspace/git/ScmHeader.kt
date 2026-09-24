package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.screens.workspace.ChromeButton
import dev.easyide.app.ui.screens.workspace.GitPanelState
import dev.easyide.app.ui.screens.workspace.SourceControlCallbacks
import dev.easyide.app.ui.theme.ControlSize
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.Stroke
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.app.ui.theme.sectionHeader
import dev.easyide.app.ui.theme.tabular
import dev.easyide.sandbox.git.GitRepoState
import dev.easyide.sandbox.git.GitStatus

/** The pane's title row, branch chip and remote actions. */
@Composable
internal fun ScmHeader(state: GitPanelState, callbacks: SourceControlCallbacks) {
    val colors = editorColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.m, end = Spacing.xs, top = Spacing.s, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.git_title),
            style = MaterialTheme.typography.sectionHeader,
            color = colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = callbacks.onRefresh,
            enabled = !state.busy,
            modifier = Modifier.minimumInteractiveComponentSize().size(ControlSize.headerAction),
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.git_refresh),
                tint = if (state.busy) colors.textDisabled else colors.textMuted,
                modifier = Modifier.size(IconSize.s),
            )
        }
    }

    val status = state.status
    if (state.isRepository && status != null) {
        BranchChip(status, onClick = callbacks.git.branches::openBranches)
        if (state.remotes.isEmpty()) NoRemote(callbacks.git.branches::openRemotes) else SyncBar(state, status, callbacks.git)
        RepoStateBanner(status.state)
    }

    // Reserved height either way, so the list does not jump when work starts.
    Box(Modifier.fillMaxWidth().height(Stroke.accentBar)) {
        if (state.busy || state.operation?.status == OperationStatus.RUNNING) {
            LinearProgressIndicator(modifier = Modifier.fillMaxSize(), color = colors.accent, trackColor = colors.panel)
        }
    }
}

@Composable
private fun BranchChip(status: GitStatus, onClick: () -> Unit) {
    val colors = editorColors
    val description = stringResource(R.string.git_branch_chip_cd, status.branch)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.git_branches_open), onClick = onClick)
            .minimumInteractiveComponentSize()
            .padding(horizontal = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Icon(
            Icons.Filled.AccountTree,
            contentDescription = description,
            tint = colors.textMuted,
            modifier = Modifier.size(IconSize.m),
        )
        Text(
            text = status.branch,
            style = MaterialTheme.typography.labelLarge,
            color = colors.plainText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (status.upstream != null) Counts(status.ahead, status.behind)
    }
}

/** Ahead and behind as arrow + number; hidden when both are zero (in sync). */
@Composable
private fun Counts(ahead: Int, behind: Int) {
    val colors = editorColors
    val style = MaterialTheme.typography.labelSmall.tabular()
    val aheadCd = pluralStringResource(R.plurals.git_ahead_cd, ahead, ahead)
    val behindCd = pluralStringResource(R.plurals.git_behind_cd, behind, behind)
    if (ahead > 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ArrowUpward, aheadCd, tint = colors.textMuted, modifier = Modifier.size(IconSize.xs))
            Text("$ahead", style = style, color = colors.textMuted)
        }
    }
    if (behind > 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ArrowDownward, behindCd, tint = colors.textMuted, modifier = Modifier.size(IconSize.xs))
            Text("$behind", style = style, color = colors.textMuted)
        }
    }
}

@Composable
private fun NoRemote(onAdd: () -> Unit) {
    val colors = editorColors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.git_no_remote),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        ChromeButton(
            text = stringResource(R.string.git_remote_add),
            onClick = onAdd,
            modifier = Modifier.minimumInteractiveComponentSize(),
        )
    }
}

@Composable
private fun SyncBar(state: GitPanelState, status: GitStatus, git: GitControllers) {
    val idle = !state.busy && state.operation?.status != OperationStatus.RUNNING
    val canPush = idle && GitRemotePlanner.pushOp(status, state.remotes) != null
    val publish = status.upstream == null
    var menu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SyncAction(Icons.Filled.Sync, R.string.git_fetch, idle, git.remote::fetch)
        SyncAction(
            Icons.Filled.ArrowDownward, R.string.git_pull,
            enabled = idle && status.upstream != null && !status.detached,
            onClick = git.remote::pull,
            count = status.behind,
        )
        SyncAction(
            Icons.Filled.ArrowUpward, if (publish) R.string.git_publish else R.string.git_push,
            enabled = canPush,
            onClick = git.remote::push,
            count = status.ahead,
        )
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(Icons.Filled.MoreVert, stringResource(R.string.git_more_actions), modifier = Modifier.size(IconSize.m))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.git_remotes_title)) },
                    onClick = { menu = false; git.branches.openRemotes() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.git_stashes_menu, state.stashes.size)) },
                    onClick = { menu = false; git.branches.openStashes() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.git_force_push)) },
                    enabled = canPush && !publish,
                    onClick = { menu = false; git.remote.requestForcePush() },
                )
            }
        }
    }
}

@Composable
private fun RowScope.SyncAction(
    icon: ImageVector,
    label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    count: Int = 0,
) {
    val colors = editorColors
    val tint = if (enabled) colors.accent else colors.textDisabled
    val text = stringResource(label)
    Row(
        modifier = Modifier
            .weight(1f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = text, tint = tint, modifier = Modifier.size(IconSize.m))
        if (count > 0) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = tint,
                modifier = Modifier.padding(start = Spacing.xs),
            )
        }
    }
}

@Composable
private fun RepoStateBanner(state: GitRepoState) {
    val res = when (state) {
        GitRepoState.NORMAL -> return
        GitRepoState.MERGING -> R.string.git_state_merging
        GitRepoState.REBASING -> R.string.git_state_rebasing
        GitRepoState.OTHER -> R.string.git_state_other
    }
    val colors = editorColors
    Text(
        text = stringResource(res),
        style = MaterialTheme.typography.labelSmall,
        color = colors.warning,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.raised)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
    )
}
