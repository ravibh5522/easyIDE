package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.ui.components.EmptyState
import dev.easyide.app.ui.components.SkeletonBar
import dev.easyide.app.ui.screens.workspace.git.CommitGraph
import dev.easyide.app.ui.screens.workspace.git.GraphRow
import dev.easyide.app.ui.screens.workspace.git.commitGraph
import dev.easyide.app.ui.theme.ControlSize
import dev.easyide.app.ui.theme.EasyIdeFonts
import dev.easyide.app.ui.theme.GitColors
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.Stroke
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.app.ui.theme.sectionHeader
import dev.easyide.app.ui.theme.tabular
import dev.easyide.sandbox.git.GitChange
import dev.easyide.sandbox.git.GitChangeType
import dev.easyide.sandbox.git.GitStatus

/**
 * Source control panel: the working tree as two lists, and a commit box.
 *
 * Deliberately shaped like VS Code's - staged above, unstaged below, a status
 * letter per row, per-row stage/unstage/discard - because that layout is what
 * users already read fluently, and none of it needs anything but the typed
 * [GitStatus] JGit already returns.
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

    Column(modifier = modifier.background(colors.panel)) {
        PaneHeader(
            branch = state.status?.branch,
            busy = state.busy,
            onRefresh = callbacks.onRefresh,
        )

        // Reserved height either way, so the list does not jump when a refresh starts.
        Box(Modifier.fillMaxWidth().height(Stroke.accentBar)) {
            if (state.busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    color = colors.accent,
                    trackColor = colors.panel,
                )
            }
        }

        state.error?.let { ErrorRow(it) }

        when {
            !state.isRepository -> NotARepository(callbacks.onInitRepository)
            state.status == null -> SkeletonRows()
            else -> RepositoryBody(state.status, state.commitMessage, state.busy, graph, callbacks)
        }
    }
}

@Composable
private fun PaneHeader(branch: String?, busy: Boolean, onRefresh: () -> Unit) {
    val colors = editorColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.m, end = Spacing.xs, top = Spacing.s, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "SOURCE CONTROL",
            style = MaterialTheme.typography.sectionHeader,
            color = colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        branch?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = colors.plainText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = Spacing.s),
            )
        }
        IconButton(
            onClick = onRefresh,
            enabled = !busy,
            modifier = Modifier.size(ControlSize.headerAction),
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Refresh",
                tint = if (busy) colors.textDisabled else colors.textMuted,
                modifier = Modifier.size(IconSize.s),
            )
        }
    }
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
            title = "Not a repository",
            body = "Initialise git here to start tracking changes.",
        )
        ChromeButton(
            text = "Initialise repository",
            onClick = onInit,
            style = ChromeButtonStyle.PRIMARY,
            modifier = Modifier.padding(top = Spacing.l),
        )
    }
}

@Composable
private fun RepositoryBody(
    status: GitStatus,
    message: String,
    busy: Boolean,
    graph: List<GraphRow>,
    callbacks: SourceControlCallbacks,
) {
    val colors = editorColors
    val canCommit = message.isNotBlank() && status.staged.isNotEmpty() && !busy

    DenseTextField(
        value = message,
        onValueChange = callbacks.onMessageChanged,
        placeholder = "Message",
        maxLines = COMMIT_MESSAGE_MAX_LINES,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
    )

    ChromeButton(
        text = commitLabel(status),
        onClick = callbacks.onCommit,
        enabled = canCommit,
        style = ChromeButtonStyle.PRIMARY,
        icon = Icons.Filled.Check,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
    )

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (status.isClean) {
            item {
                Text(
                    text = "No changes",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.fillMaxWidth().padding(Spacing.m),
                )
            }
        }
        if (status.conflicting.isNotEmpty()) {
            item { SectionHeader("Merge changes", status.conflicting.size, null) }
            items(status.conflicting, key = { "c:${it.path}" }) { change ->
                ChangeRow(change, busy, callbacks)
            }
        }
        if (status.staged.isNotEmpty()) {
            item {
                SectionHeader("Staged changes", status.staged.size) {
                    callbacks.onUnstage(status.staged.map { it.path })
                }
            }
            items(status.staged, key = { "s:${it.path}" }) { change ->
                ChangeRow(change, busy, callbacks)
            }
        }
        if (status.unstaged.isNotEmpty()) {
            item {
                SectionHeader("Changes", status.unstaged.size) {
                    callbacks.onStage(status.unstaged.map { it.path })
                }
            }
            items(status.unstaged, key = { "u:${it.path}" }) { change ->
                ChangeRow(change, busy, callbacks)
            }
        }

        if (graph.isNotEmpty()) {
            item { SectionHeader("Graph", graph.size, null) }
            commitGraph(graph) { }
        }
    }
}

private fun commitLabel(status: GitStatus): String = when {
    status.staged.isEmpty() -> "Commit"
    else -> "Commit ${status.staged.size} file${if (status.staged.size == 1) "" else "s"}"
}

/** [onBulkAction] stages or unstages the whole section, matching VS Code's +/- affordance. */
@Composable
private fun SectionHeader(title: String, count: Int, onBulkAction: (() -> Unit)?) {
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
        onBulkAction?.let {
            ChromeButton(
                text = if (title.startsWith("Staged")) "Unstage all" else "Stage all",
                onClick = it,
            )
        }
    }
}

@Composable
private fun ChangeRow(change: GitChange, busy: Boolean, callbacks: SourceControlCallbacks) {
    val colors = editorColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { callbacks.onOpenFile(change.path) }
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
            RowAction(Icons.Filled.Undo, "Discard", busy) { callbacks.onDiscard(listOf(change.path)) }
            RowAction(Icons.Filled.Add, "Stage", busy) { callbacks.onStage(listOf(change.path)) }
        } else {
            RowAction(Icons.Filled.Remove, "Unstage", busy) { callbacks.onUnstage(listOf(change.path)) }
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

private const val COMMIT_MESSAGE_MAX_LINES = 3

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
