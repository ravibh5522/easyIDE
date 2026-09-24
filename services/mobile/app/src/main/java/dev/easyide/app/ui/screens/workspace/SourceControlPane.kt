package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.easyide.app.ui.screens.workspace.git.ChangeRow
import dev.easyide.app.ui.screens.workspace.git.CommitGraph
import dev.easyide.app.ui.screens.workspace.git.GitConfirm
import dev.easyide.app.ui.shell.DocumentOpener
import dev.easyide.app.ui.shell.diff.GitDocuments
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
import dev.easyide.sandbox.git.GitChange
import dev.easyide.sandbox.git.GitStatus

/**
 * Source control panel: branch and remote actions, a commit box, then the working tree and the
 * graph as collapsible sections with counts.
 *
 * Deliberately shaped like VS Code's - staged above, unstaged below, one line per change with
 * a status letter and the stage/unstage/discard actions on the selected row - because that
 * layout is what users already read fluently. A tap opens the row's diff as a stage document
 * (a preview; a double tap keeps it, the row's menu sends it to the side);
 * merge-conflict rows open the file itself, where the conflict markers are.
 * A commit in the graph opens as a document the same way.
 */
@Composable
fun SourceControlPane(
    state: GitPanelState,
    callbacks: SourceControlCallbacks,
    opener: DocumentOpener,
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
            else -> RepositoryBody(state, state.status, graph, callbacks, opener)
        }
    }

    when (state.sheet) {
        GitSheet.BRANCHES -> BranchSheet(state.branches, git.branches)
        GitSheet.REMOTES -> RemoteSheet(state.remotes, git.remote, git.branches::closeSheet)
        GitSheet.STASHES -> StashSheet(state.status, state.stashes, git.branches)
        null -> Unit
    }
    // A discarded hunk is confirmed by the diff document that asked, which is on screen when this pane may not be.
    state.confirm?.takeIf { it !is GitConfirm.DiscardHunk }?.let { GitConfirmDialog(it, git.commit::answerConfirm) }
    tokenHost?.let { host ->
        GitCredentialDialog(
            initialHost = host,
            onSave = git.remote::saveToken,
            onDismiss = { tokenHost = null },
        )
    }
}

@Composable
private fun RepositoryBody(
    state: GitPanelState,
    status: GitStatus,
    graph: List<GraphRow>,
    callbacks: SourceControlCallbacks,
    opener: DocumentOpener,
) {
    val busy = state.busy
    val closed = rememberClosedSections()
    // The change whose row shows its actions, like VS Code's hovered row: a tap on a row selects it.
    var revealed by rememberSaveable { mutableStateOf<String?>(null) }
    val row: @Composable (GitChange, String) -> Unit = { change, id ->
        ChangeRow(change, busy, revealed == id, { revealed = id }, callbacks, opener)
    }

    val noChanges = stringResource(R.string.git_no_changes)
    val titles = SectionTitles(
        stringResource(R.string.git_section_conflicts), stringResource(R.string.git_section_staged),
        stringResource(R.string.git_section_changes), stringResource(R.string.git_section_graph),
    )
    val stageAll = stringResource(R.string.git_stage_all)
    val unstageAll = stringResource(R.string.git_unstage_all)

    ScmCommitBox(state, status, callbacks)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (status.isClean) item { KitRow(noChanges, enabled = false) }
        if (status.conflicting.isNotEmpty()) {
            panelSection(closed, "conflicts", titles.conflicts, status.conflicting.size) {
                items(status.conflicting, key = { "c:${it.path}" }) { row(it, "c:${it.path}") }
            }
        }
        if (status.staged.isNotEmpty()) {
            panelSection(
                closed, "staged", titles.staged, status.staged.size,
                actions = { KitIconButton(Icons.Filled.Remove, unstageAll, { callbacks.onUnstage(status.staged.map { it.path }) }, enabled = !busy) },
            ) {
                items(status.staged, key = { "s:${it.path}" }) { row(it, "s:${it.path}") }
            }
        }
        if (status.unstaged.isNotEmpty()) {
            panelSection(
                closed, "changes", titles.changes, status.unstaged.size,
                actions = { KitIconButton(Icons.Filled.Add, stageAll, { callbacks.onStage(status.unstaged.map { it.path }) }, enabled = !busy) },
            ) {
                items(status.unstaged, key = { "u:${it.path}" }) { row(it, "u:${it.path}") }
            }
        }
        if (graph.isNotEmpty()) {
            panelSection(closed, "graph", titles.graph, graph.size) {
                commitGraph(graph) { sha -> GitDocuments.commitUri(sha)?.let(opener::preview) }
            }
        }
    }
}

/** The section titles, read in composition so the list's builder lambdas need no resources. */
private class SectionTitles(val conflicts: String, val staged: String, val changes: String, val graph: String)

/**
 * Stand-in rows while the first status read is in flight, the row token tall like change rows
 * (name, then a shorter directory) so the real list replaces them without a jump.
 */
@Composable
private fun SkeletonRows() {
    val colors = Kit.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Kit.control.hPad)) {
        SKELETON_ROW_WIDTHS.forEach { fraction ->
            Row(Modifier.fillMaxWidth().height(Kit.control.rowHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.s)) {
                SkeletonBar(colors.raised, Kit.space.m, Modifier.fillMaxWidth(fraction))
                SkeletonBar(colors.raised, Kit.space.s, Modifier.fillMaxWidth(fraction / 2))
            }
        }
    }
}

/** Varied widths read as a list of names; equal bars read as a broken layout. */
private val SKELETON_ROW_WIDTHS = listOf(0.7f, 0.5f, 0.85f, 0.6f, 0.4f)
