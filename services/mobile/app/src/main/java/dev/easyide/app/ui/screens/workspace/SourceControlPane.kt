package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.ui.components.EmptyState
import dev.easyide.app.ui.components.SkeletonBar
import dev.easyide.app.R
import dev.easyide.app.ui.screens.workspace.git.BranchSheet
import dev.easyide.app.ui.screens.workspace.git.CommitGraph
import dev.easyide.app.ui.screens.workspace.git.DiffScreen
import dev.easyide.app.ui.screens.workspace.git.GitConfirmDialog
import dev.easyide.app.ui.screens.workspace.git.GitCredentialDialog
import dev.easyide.app.ui.screens.workspace.git.GitSheet
import dev.easyide.app.ui.screens.workspace.git.OperationPanel
import dev.easyide.app.ui.screens.workspace.git.RemoteSheet
import dev.easyide.app.ui.screens.workspace.git.ScmCommitBox
import dev.easyide.app.ui.screens.workspace.git.ScmHeader
import dev.easyide.app.ui.screens.workspace.git.StashSheet
import dev.easyide.app.ui.screens.workspace.git.GraphRow
import dev.easyide.app.ui.screens.workspace.git.commitGraph
import dev.easyide.app.ui.theme.ControlSize
import dev.easyide.app.ui.theme.EasyIdeFonts
import dev.easyide.app.ui.theme.GitColors
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.app.ui.theme.sectionHeader
import dev.easyide.app.ui.theme.tabular
import dev.easyide.sandbox.git.DiffSource
import dev.easyide.sandbox.git.GitChange
import dev.easyide.sandbox.git.GitChangeType
import dev.easyide.sandbox.git.GitStatus

/**
 * Source control panel: branch and remote actions, the working tree as two
 * lists, a commit box and the graph.
 *
 * Deliberately shaped like VS Code's - staged above, unstaged below, a status
 * letter per row, per-row stage/unstage/discard - because that layout is what
 * users already read fluently. Tapping a row opens its diff; merge-conflict
 * rows open the file itself, where the conflict markers are.
 */
@Composable
fun SourceControlPane(
    state: GitPanelState,
    callbacks: SourceControlCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = editorColors
    // Lane assignment is pure and depends only on the commit list, so it is
    // memoised here rather than recomputed on every recomposition of the list.
    val graph = remember(state.commits) { CommitGraph.build(state.commits) }
    var tokenHost by remember { mutableStateOf<String?>(null) }
    val git = callbacks.git

    Column(modifier = modifier.background(colors.panel)) {
        ScmHeader(state, callbacks)

        state.operation?.let { op ->
            OperationPanel(op, onCancel = git.remote::cancel, onDismiss = git.remote::dismissOperation, onAddToken = { tokenHost = it })
        }
        state.error?.let { ErrorRow(it) }

        when {
            !state.isRepository -> NotARepository(callbacks.onInitRepository)
            state.status == null -> SkeletonRows()
            else -> RepositoryBody(state, state.status, graph, callbacks)
        }
    }

    when (state.sheet) {
        GitSheet.BRANCHES -> BranchSheet(state.branches, git.branches)
        GitSheet.REMOTES -> RemoteSheet(state.remotes, git.remote, git.branches::closeSheet)
        GitSheet.STASHES -> StashSheet(state.status, state.stashes, git.branches)
        null -> Unit
    }
    state.confirm?.let { GitConfirmDialog(it, git.commit::answerConfirm) }
    tokenHost?.let { host ->
        GitCredentialDialog(
            initialHost = host,
            onSave = git.remote::saveToken,
            onDismiss = { tokenHost = null },
        )
    }
    if (state.diff != null) DiffScreen(state, callbacks)
}

@Composable
private fun ErrorRow(message: String) {
    val colors = editorColors
    Text(
        text = message,
        style = MaterialTheme.typography.labelSmall,
        color = colors.error,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
    )
}

@Composable
private fun NotARepository(onInit: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            icon = Icons.Filled.Difference,
            title = stringResource(R.string.git_not_repo_title),
            body = stringResource(R.string.git_not_repo_body),
        )
        ChromeButton(
            text = stringResource(R.string.git_init),
            onClick = onInit,
            style = ChromeButtonStyle.PRIMARY,
            modifier = Modifier.padding(top = Spacing.l),
        )
    }
}

@Composable
private fun RepositoryBody(
    state: GitPanelState,
    status: GitStatus,
    graph: List<GraphRow>,
    callbacks: SourceControlCallbacks,
) {
    val colors = editorColors
    val busy = state.busy

    ScmCommitBox(state, status, callbacks)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (status.isClean) {
            item {
                Text(
                    text = stringResource(R.string.git_no_changes),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.fillMaxWidth().padding(Spacing.m),
                )
            }
        }
        if (status.conflicting.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.git_section_conflicts), status.conflicting.size, null, null) }
            items(status.conflicting, key = { "c:${it.path}" }) { change ->
                ChangeRow(change, busy, callbacks)
            }
        }
        if (status.staged.isNotEmpty()) {
            item {
                SectionHeader(
                    stringResource(R.string.git_section_staged), status.staged.size,
                    stringResource(R.string.git_unstage_all),
                ) { callbacks.onUnstage(status.staged.map { it.path }) }
            }
            items(status.staged, key = { "s:${it.path}" }) { change ->
                ChangeRow(change, busy, callbacks)
            }
        }
        if (status.unstaged.isNotEmpty()) {
            item {
                SectionHeader(
                    stringResource(R.string.git_section_changes), status.unstaged.size,
                    stringResource(R.string.git_stage_all),
                ) { callbacks.onStage(status.unstaged.map { it.path }) }
            }
            items(status.unstaged, key = { "u:${it.path}" }) { change ->
                ChangeRow(change, busy, callbacks)
            }
        }

        if (graph.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.git_section_graph), graph.size, null, null) }
            commitGraph(graph) { }
        }
    }
}

/** [onBulkAction], labelled [bulkLabel], stages or unstages the whole section, matching VS Code's +/- affordance. */
@Composable
private fun SectionHeader(title: String, count: Int, bulkLabel: String?, onBulkAction: (() -> Unit)?) {
    val colors = editorColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.m, end = Spacing.xs, top = Spacing.m, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.sectionHeader,
            color = colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall.tabular(),
            color = colors.textMuted,
        )
        if (onBulkAction != null && bulkLabel != null) ChromeButton(text = bulkLabel, onClick = onBulkAction)
    }
}

@Composable
private fun ChangeRow(change: GitChange, busy: Boolean, callbacks: SourceControlCallbacks) {
    val colors = editorColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openChange(change, callbacks) }
            .padding(start = Spacing.m, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = change.name,
                style = MaterialTheme.typography.bodySmall,
                color = colors.plainText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (change.directory.isNotEmpty()) {
                Text(
                    text = change.directory,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!change.staged) {
            RowAction(Icons.Filled.Undo, stringResource(R.string.git_discard), busy) { callbacks.onDiscard(listOf(change.path)) }
            RowAction(Icons.Filled.Add, stringResource(R.string.git_stage), busy) { callbacks.onStage(listOf(change.path)) }
        } else {
            RowAction(Icons.Filled.Remove, stringResource(R.string.git_unstage), busy) { callbacks.onUnstage(listOf(change.path)) }
        }

        Text(
            text = change.type.letter,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = EasyIdeFonts.mono,
            color = change.type.tint(colors.git),
            modifier = Modifier.padding(horizontal = Spacing.s),
        )
    }
}

private fun openChange(change: GitChange, callbacks: SourceControlCallbacks) {
    when {
        change.type == GitChangeType.CONFLICTED -> callbacks.onOpenFile(change.path)
        change.staged -> callbacks.git.diff.open(change.path, DiffSource.STAGED)
        else -> callbacks.git.diff.open(change.path, DiffSource.UNSTAGED)
    }
}

@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val colors = editorColors
    IconButton(onClick = onClick, enabled = !busy, modifier = Modifier.size(ControlSize.row)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (busy) colors.textDisabled else colors.textMuted,
            modifier = Modifier.size(IconSize.s),
        )
    }
}

/**
 * Stand-in rows while the first status read is in flight, shaped like change
 * rows (name over directory) so the real list replaces them without a jump.
 */
@Composable
private fun SkeletonRows() {
    val colors = editorColors
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        SKELETON_ROW_WIDTHS.forEach { fraction ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                SkeletonBar(colors.raised, Spacing.m, Modifier.fillMaxWidth(fraction))
                SkeletonBar(colors.raised, Spacing.s, Modifier.fillMaxWidth(fraction / 2))
            }
        }
    }
}

/** Varied widths read as a list of names; equal bars read as a broken layout. */
private val SKELETON_ROW_WIDTHS = listOf(0.7f, 0.5f, 0.85f, 0.6f, 0.4f)

private val GitChangeType.letter: String
    get() = when (this) {
        GitChangeType.ADDED -> "A"
        GitChangeType.MODIFIED -> "M"
        GitChangeType.DELETED -> "D"
        GitChangeType.UNTRACKED -> "U"
        GitChangeType.CONFLICTED -> "C"
    }

private fun GitChangeType.tint(git: GitColors): Color = when (this) {
    GitChangeType.ADDED -> git.added
    GitChangeType.MODIFIED -> git.modified
    GitChangeType.DELETED -> git.deleted
    GitChangeType.CONFLICTED -> git.conflict
    GitChangeType.UNTRACKED -> git.untracked
}
