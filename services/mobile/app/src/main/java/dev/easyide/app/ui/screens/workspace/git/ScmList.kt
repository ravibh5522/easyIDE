package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.Twistie
import dev.easyide.app.ui.screens.workspace.GitPanelState
import dev.easyide.app.ui.screens.workspace.SourceControlCallbacks
import dev.easyide.app.ui.screens.workspace.panelSection
import dev.easyide.app.ui.screens.workspace.rememberClosedSections
import dev.easyide.app.ui.shell.DocumentOpener
import dev.easyide.app.ui.shell.diff.GitDocuments
import dev.easyide.sandbox.git.GitChange
import dev.easyide.sandbox.git.GitStatus

/**
 * The repository's body under the commit box: merge conflicts, staged changes, changes and the graph as
 * collapsible sections with counts and toolbars, the change sections as a flat list or a folder tree
 * ([treeView]). One row is selected at a time, which is the row whose actions show without a hover.
 */
@Composable
internal fun RepositoryBody(
    state: GitPanelState,
    status: GitStatus,
    graph: List<GraphRow>,
    callbacks: SourceControlCallbacks,
    opener: DocumentOpener,
    treeView: Boolean,
) {
    val git = callbacks.git
    val busy = state.busy
    val closed = rememberClosedSections()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var ask by remember { mutableStateOf<HistoryAsk?>(null) }
    val env = CommitMenuEnv(
        git = git, hasUpstream = state.upstream != null, busy = busy,
        onOpenChanges = { sha -> GitDocuments.commitUri(sha)?.let(opener::preview) }, onAsk = { ask = it },
    )
    val rows = ChangeRows(
        tree = treeView, collapsed = collapsed,
        toggle = { key -> collapsed = if (key in collapsed) collapsed - key else collapsed + key },
    )
    val row: @Composable (GitChange, String, ChangeRowLayout) -> Unit = { change, id, layout ->
        ChangeRow(change, busy, selected == id, { selected = id }, callbacks, opener, layout)
    }
    val noChanges = stringResource(R.string.git_no_changes)
    val conflicts = stringResource(R.string.git_section_conflicts)
    val staged = stringResource(R.string.git_section_staged)
    val changes = stringResource(R.string.git_section_changes)
    val graphTitle = stringResource(R.string.git_section_graph)
    val stageAll = stringResource(R.string.git_stage_all)
    val unstageAll = stringResource(R.string.git_unstage_all)
    val discardAll = stringResource(R.string.gitui_discard_all)
    val fetch = stringResource(R.string.gitui_graph_fetch)
    val refresh = stringResource(R.string.git_refresh)
    val idle = !busy && state.operation?.status != OperationStatus.RUNNING

    ScmCommitBox(state, status, callbacks)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (status.isClean) item { KitRow(noChanges, enabled = false) }
        if (status.conflicting.isNotEmpty()) {
            panelSection(closed, "conflicts", conflicts, status.conflicting.size) { changeRows("c", status.conflicting, rows, row) }
        }
        if (status.staged.isNotEmpty()) {
            panelSection(
                closed, "staged", staged, status.staged.size,
                actions = { KitIconButton(Icons.Filled.Remove, unstageAll, { callbacks.onUnstage(status.staged.map { it.path }) }, enabled = !busy) },
            ) { changeRows("s", status.staged, rows, row) }
        }
        if (status.unstaged.isNotEmpty()) {
            panelSection(
                closed, "changes", changes, status.unstaged.size,
                actions = {
                    KitIconButton(Icons.Filled.Undo, discardAll, { callbacks.onDiscard(status.unstaged.map { it.path }) }, enabled = !busy)
                    KitIconButton(Icons.Filled.Add, stageAll, { callbacks.onStage(status.unstaged.map { it.path }) }, enabled = !busy)
                },
            ) { changeRows("u", status.unstaged, rows, row) }
        }
        if (graph.isNotEmpty()) {
            panelSection(
                closed, "graph", graphTitle, graph.size,
                actions = {
                    KitIconButton(Icons.Filled.CloudDownload, fetch, git.remote::fetch, enabled = idle && state.remotes.isNotEmpty())
                    KitIconButton(Icons.Filled.Refresh, refresh, callbacks.onRefresh, enabled = !busy)
                },
            ) {
                commitGraph(
                    graph,
                    GraphRowActions(
                        refsOf = { state.refs[it].orEmpty() },
                        selected = selected,
                        onSelect = { selected = it },
                        onOpen = { sha -> GitDocuments.commitUri(sha)?.let(opener::preview) },
                        menuFor = { row, refs -> commitMenuItems(row, refs, env) },
                    ),
                )
            }
        }
    }

    ask?.let { HistoryAskDialog(it, state.branches, git) { ask = null } }
    state.comparison?.let { ComparisonDialog(it, opener, git.history::closeComparison) }
}

/** How the change sections are laid out; the id of a change is its section prefix and its path. */
private class ChangeRows(
    val tree: Boolean,
    val collapsed: Set<String>,
    val toggle: (String) -> Unit,
)

/** One section's rows: a flat list, or the folder tree with a row per folder that folds its files away. */
private fun LazyListScope.changeRows(
    prefix: String,
    changes: List<GitChange>,
    rows: ChangeRows,
    row: @Composable (GitChange, String, ChangeRowLayout) -> Unit,
) {
    if (!rows.tree) {
        items(changes, key = { "$prefix:${it.path}" }) { row(it, "$prefix:${it.path}", ChangeRowLayout()) }
        return
    }
    val closedHere = rows.collapsed.filter { it.startsWith("$prefix:") }.map { it.removePrefix("$prefix:") }.toSet()
    val flat = flattenChanges(changes, closedHere)
    items(flat, key = { item -> if (item is ChangeItem.Folder) "$prefix/dir:${item.path}" else "$prefix:${(item as ChangeItem.File).change.path}" }) { item ->
        when (item) {
            is ChangeItem.Folder -> {
                val open = item.path !in closedHere
                KitRow(
                    title = item.label,
                    leading = { Image(Icons.Filled.Folder, null, Modifier.size(Kit.control.rowIcon), colorFilter = ColorFilter.tint(Kit.colors.textMuted)) },
                    onClick = { rows.toggle("$prefix:${item.path}") },
                    level = item.level,
                    twistie = if (open) Twistie.Expanded else Twistie.Collapsed,
                )
            }
            is ChangeItem.File -> row(item.change, "$prefix:${item.change.path}", ChangeRowLayout(item.level, showDirectory = false, tree = true))
        }
    }
}
