package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.components.EmptyState
import dev.easyide.app.ui.screens.workspace.git.CommitGraph
import dev.easyide.app.ui.screens.workspace.git.GraphRow
import dev.easyide.app.ui.screens.workspace.git.commitGraph
import dev.easyide.app.ui.theme.editorColors
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

        state.error?.let { ErrorRow(it) }

        when {
            !state.isRepository -> NotARepository(callbacks.onInitRepository)
            state.status == null -> Box(Modifier.fillMaxSize())
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
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "SOURCE CONTROL",
            style = MaterialTheme.typography.labelSmall,
            color = colors.gutterText,
            modifier = Modifier.weight(1f),
        )
        branch?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = colors.plainText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = "Refresh",
            tint = if (busy) colors.gutterText else colors.plainText,
            modifier = Modifier
                .size(32.dp)
                .clickable(enabled = !busy, onClick = onRefresh)
                .padding(6.dp),
        )
    }
}

@Composable
private fun ErrorRow(message: String) {
    val colors = editorColors
    Text(
        text = message,
        style = MaterialTheme.typography.labelSmall,
        color = colors.syntax.invalid,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun NotARepository(onInit: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            icon = Icons.Filled.Difference,
            title = "Not a repository",
            body = "Initialise git here to start tracking changes.",
        )
        Button(onClick = onInit, modifier = Modifier.padding(top = 16.dp)) {
            Text("Initialise repository")
        }
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

    OutlinedTextField(
        value = message,
        onValueChange = callbacks.onMessageChanged,
        placeholder = { Text("Message", style = MaterialTheme.typography.bodySmall) },
        textStyle = MaterialTheme.typography.bodySmall,
        singleLine = false,
        maxLines = 3,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )

    Button(
        onClick = callbacks.onCommit,
        enabled = canCommit,
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(
            text = commitLabel(status),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (status.isClean) {
            item {
                Text(
                    text = "No changes",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.gutterText,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
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
            .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.gutterText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = colors.gutterText,
        )
        onBulkAction?.let {
            TextButton(onClick = it, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                Text(if (title.startsWith("Staged")) "Unstage all" else "Stage all",
                    style = MaterialTheme.typography.labelSmall)
            }
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
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
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
                    color = colors.gutterText,
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
            fontFamily = FontFamily.Monospace,
            color = change.type.tint(colors.syntax.string, colors.syntax.keyword, colors.syntax.invalid, colors.gutterText),
            modifier = Modifier.padding(horizontal = 6.dp),
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
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = colors.gutterText,
        modifier = Modifier
            .size(28.dp)
            .clickable(enabled = !busy, onClick = onClick)
            .padding(5.dp),
    )
}

private val GitChangeType.letter: String
    get() = when (this) {
        GitChangeType.ADDED -> "A"
        GitChangeType.MODIFIED -> "M"
        GitChangeType.DELETED -> "D"
        GitChangeType.UNTRACKED -> "U"
        GitChangeType.CONFLICTED -> "C"
    }

private fun GitChangeType.tint(
    added: Color,
    modified: Color,
    conflicted: Color,
    untracked: Color,
): Color = when (this) {
    GitChangeType.ADDED -> added
    GitChangeType.MODIFIED -> modified
    GitChangeType.DELETED -> conflicted
    GitChangeType.CONFLICTED -> conflicted
    GitChangeType.UNTRACKED -> untracked
}
