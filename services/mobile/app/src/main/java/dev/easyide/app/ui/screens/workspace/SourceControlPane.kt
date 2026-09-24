package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Undo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.components.SkeletonBar
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.screens.workspace.git.BranchSheet
import dev.easyide.app.ui.screens.workspace.git.CommitGraph
import dev.easyide.app.ui.screens.workspace.git.DiffScreen
import dev.easyide.app.ui.screens.workspace.git.GitConfirmDialog
import dev.easyide.app.ui.screens.workspace.git.GitCredentialDialog
import dev.easyide.app.ui.screens.workspace.git.GitSheet
import dev.easyide.app.ui.screens.workspace.git.GraphRow
import dev.easyide.app.ui.screens.workspace.git.OperationPanel
import dev.easyide.app.ui.screens.workspace.git.RemoteSheet
import dev.easyide.app.ui.screens.workspace.git.ScmCommitBox
import dev.easyide.app.ui.screens.workspace.git.ScmHeader
import dev.easyide.app.ui.screens.workspace.git.StashSheet
import dev.easyide.app.ui.screens.workspace.git.commitGraph
import dev.easyide.app.ui.theme.EasyIdeFonts
import dev.easyide.app.ui.theme.GitColors
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
    // Lane assignment is pure and depends only on the commit list, so it is
    // memoised here rather than recomputed on every recomposition of the list.
    val graph = remember(state.commits) { CommitGraph.build(state.commits) }
    var tokenHost by remember { mutableStateOf<String?>(null) }
    val git = callbacks.git

    Column(modifier = modifier.background(Kit.colors.panel)) {
        ScmHeader(state, callbacks)

        state.operation?.let { op ->
            OperationPanel(op, onCancel = git.remote::cancel, onDismiss = git.remote::dismissOperation, onAddToken = { tokenHost = it })
        }
        state.error?.let { KitBanner(it, tone = Tone.Danger) }

        when {
            !state.isRepository -> KitEmptyState(
                art = EmptyArt.Git,
                message = stringResource(R.string.git_not_repo_body),
                action = KitAction(stringResource(R.string.git_init), callbacks.onInitRepository),
            )
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
private fun RepositoryBody(
    state: GitPanelState,
    status: GitStatus,
    graph: List<GraphRow>,
    callbacks: SourceControlCallbacks,
) {
    val busy = state.busy

    ScmCommitBox(state, status, callbacks)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (status.isClean) {
            item { KitEmptyState(art = EmptyArt.Git, message = stringResource(R.string.git_no_changes)) }
        }
        if (status.conflicting.isNotEmpty()) {
            item { PanelGroupHeader(stringResource(R.string.git_section_conflicts), status.conflicting.size) }
            items(status.conflicting, key = { "c:${it.path}" }) { change -> ChangeRow(change, busy, callbacks) }
        }
        if (status.staged.isNotEmpty()) {
            item {
                PanelGroupHeader(
                    stringResource(R.string.git_section_staged), status.staged.size,
                    bulk = KitAction(stringResource(R.string.git_unstage_all)) { callbacks.onUnstage(status.staged.map { it.path }) },
                )
            }
            items(status.staged, key = { "s:${it.path}" }) { change -> ChangeRow(change, busy, callbacks) }
        }
        if (status.unstaged.isNotEmpty()) {
            item {
                PanelGroupHeader(
                    stringResource(R.string.git_section_changes), status.unstaged.size,
                    bulk = KitAction(stringResource(R.string.git_stage_all)) { callbacks.onStage(status.unstaged.map { it.path }) },
                )
            }
            items(status.unstaged, key = { "u:${it.path}" }) { change -> ChangeRow(change, busy, callbacks) }
        }

        if (graph.isNotEmpty()) {
            item { PanelGroupHeader(stringResource(R.string.git_section_graph), graph.size) }
            commitGraph(graph) { }
        }
    }
}

/** One change: the row opens its diff; at most two trailing actions (discard and stage, or unstage) and the status letter. */
@Composable
private fun ChangeRow(change: GitChange, busy: Boolean, callbacks: SourceControlCallbacks) {
    val colors = Kit.colors
    KitRow(
        title = change.name,
        subtitle = change.directory.ifEmpty { null },
        mono = true,
        onClick = { openChange(change, callbacks) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!change.staged) {
                    KitIconButton(Icons.Filled.Undo, stringResource(R.string.git_discard), { callbacks.onDiscard(listOf(change.path)) }, enabled = !busy)
                    KitIconButton(Icons.Filled.Add, stringResource(R.string.git_stage), { callbacks.onStage(listOf(change.path)) }, enabled = !busy)
                } else {
                    KitIconButton(Icons.Filled.Remove, stringResource(R.string.git_unstage), { callbacks.onUnstage(listOf(change.path)) }, enabled = !busy)
                }
                BasicText(
                    text = change.type.letter,
                    style = Kit.type.labelMedium.copy(fontFamily = EasyIdeFonts.mono, color = change.type.tint(colors.git)),
                )
            }
        },
    )
}

private fun openChange(change: GitChange, callbacks: SourceControlCallbacks) {
    when {
        change.type == GitChangeType.CONFLICTED -> callbacks.onOpenFile(change.path)
        change.staged -> callbacks.git.diff.open(change.path, DiffSource.STAGED)
        else -> callbacks.git.diff.open(change.path, DiffSource.UNSTAGED)
    }
}

/**
 * Stand-in rows while the first status read is in flight, shaped like change
 * rows (name over directory) so the real list replaces them without a jump.
 */
@Composable
private fun SkeletonRows() {
    val colors = Kit.colors
    val space = Kit.space
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = space.m, vertical = space.s),
        verticalArrangement = Arrangement.spacedBy(space.m),
    ) {
        SKELETON_ROW_WIDTHS.forEach { fraction ->
            Column(verticalArrangement = Arrangement.spacedBy(space.xs)) {
                SkeletonBar(colors.raised, space.m, Modifier.fillMaxWidth(fraction))
                SkeletonBar(colors.raised, space.s, Modifier.fillMaxWidth(fraction / 2))
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
