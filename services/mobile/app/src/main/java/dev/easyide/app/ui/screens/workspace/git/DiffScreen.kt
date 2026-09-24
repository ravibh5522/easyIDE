package dev.easyide.app.ui.screens.workspace.git

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.easyide.app.R
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.screens.workspace.GitPanelState
import dev.easyide.app.ui.screens.workspace.SourceControlCallbacks
import dev.easyide.app.ui.theme.EasyIdeFonts
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.sandbox.git.DiffHunk
import dev.easyide.sandbox.git.DiffSource
import dev.easyide.sandbox.git.FileDiff

/** One flattened entry of the diff list, so a LazyColumn virtualises lines, not hunks. */
private sealed interface DiffItem {
    data class Header(val hunk: DiffHunk) : DiffItem
    data class Unified(val row: DiffRow) : DiffItem
    data class Split(val row: SplitRow) : DiffItem
}

/**
 * The full-screen diff of one file: unified, or side by side at EXPANDED width.
 * Hunk headers carry the hunk-level stage / unstage / discard actions; the
 * toolbar carries the whole-file ones and the way back to the editor.
 */
@Composable
internal fun DiffScreen(state: GitPanelState, callbacks: SourceControlCallbacks) {
    val diffState = state.diff ?: return
    val git = callbacks.git
    val colors = editorColors

    Dialog(
        onDismissRequest = git.diff::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            DiffToolbar(state, diffState, callbacks)
            HorizontalDivider(color = colors.panelBorder)
            Box(modifier = Modifier.fillMaxSize()) {
                DiffBody(diffState, git.diff, busy = state.busy)
            }
        }
    }
}

@Composable
private fun DiffToolbar(state: GitPanelState, diffState: GitDiffState, callbacks: SourceControlCallbacks) {
    val colors = editorColors
    val git = callbacks.git
    val status = state.status
    val path = diffState.path
    val hasStaged = status?.staged?.any { it.path == path } == true
    val hasUnstaged = status?.unstaged?.any { it.path == path } == true
    val name = path.substringAfterLast('/')
    val directory = path.substringBeforeLast('/', "")

    Column(modifier = Modifier.fillMaxWidth().background(colors.panel)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = Spacing.s)) {
            IconButton(onClick = git.diff::close, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.git_diff_close), modifier = Modifier.size(IconSize.m))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = colors.plainText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (directory.isNotEmpty()) {
                    Text(directory, style = MaterialTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            (diffState.diff as? FileDiff.Text)?.let { text ->
                Text(
                    text = stringResource(R.string.git_diff_stats, text.added, text.removed),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = EasyIdeFonts.mono,
                    color = colors.textMuted,
                )
            }
            IconButton(
                onClick = { git.diff.close(); callbacks.onOpenFile(path) },
                modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.git_diff_open_file), modifier = Modifier.size(IconSize.m))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (hasStaged && hasUnstaged) {
                SourceTab(R.string.git_diff_unstaged, diffState.source == DiffSource.UNSTAGED) { git.diff.showSource(DiffSource.UNSTAGED) }
                SourceTab(R.string.git_diff_staged, diffState.source == DiffSource.STAGED) { git.diff.showSource(DiffSource.STAGED) }
            } else {
                Text(
                    text = stringResource(if (diffState.source == DiffSource.STAGED) R.string.git_diff_staged else R.string.git_diff_unstaged),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = Spacing.s),
                )
            }
            Box(modifier = Modifier.weight(1f))
            if (diffState.source == DiffSource.STAGED) {
                TextButton(onClick = { callbacks.onUnstage(listOf(path)) }, enabled = !state.busy, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Text(stringResource(R.string.git_diff_unstage_file))
                }
            } else {
                TextButton(onClick = { callbacks.onStage(listOf(path)) }, enabled = !state.busy, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Text(stringResource(R.string.git_diff_stage_file))
                }
            }
        }
    }
}

@Composable
private fun SourceTab(label: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = editorColors
    TextButton(onClick = onClick, modifier = Modifier.minimumInteractiveComponentSize()) {
        Text(
            text = stringResource(label),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.accent else colors.textMuted,
        )
    }
}

@Composable
private fun DiffBody(diffState: GitDiffState, controller: GitDiffController, busy: Boolean) {
    val colors = editorColors
    when (val diff = diffState.diff) {
        null -> Centered {
            if (diffState.error != null) {
                Text(diffState.error, color = colors.error, style = MaterialTheme.typography.bodyMedium)
            } else {
                CircularProgressIndicator(color = colors.accent)
            }
        }
        is FileDiff.Binary -> Message(stringResource(R.string.git_diff_binary))
        is FileDiff.TooLarge -> Message(
            stringResource(
                R.string.git_diff_too_large,
                Formatter.formatShortFileSize(LocalContext.current, diff.bytes),
            ),
        )
        is FileDiff.Text -> if (diff.hunks.isEmpty()) {
            Message(stringResource(R.string.git_diff_empty))
        } else {
            HunkList(diff, diffState.source, controller, busy)
        }
    }
}

@Composable
private fun HunkList(diff: FileDiff.Text, source: DiffSource, controller: GitDiffController, busy: Boolean) {
    val split = LocalWindowSize.current.width.isExpanded
    val items = remember(diff, split) { flatten(diff, split) }
    val leftScroll = rememberScrollState()
    val rightScroll = rememberScrollState()
    val colors = editorColors

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items) { item ->
            when (item) {
                is DiffItem.Header -> HunkHeader(item.hunk, source, controller, busy)
                is DiffItem.Unified -> UnifiedLine(item.row, leftScroll)
                is DiffItem.Split -> Row(modifier = Modifier.fillMaxWidth()) {
                    SplitHalf(item.row.left, oldSide = true, scroll = leftScroll, modifier = Modifier.weight(1f))
                    Box(modifier = Modifier.width(GitUi.dividerWidth).background(colors.panelBorder))
                    SplitHalf(item.row.right, oldSide = false, scroll = rightScroll, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun flatten(diff: FileDiff.Text, split: Boolean): List<DiffItem> = buildList {
    diff.hunks.forEach { hunk ->
        add(DiffItem.Header(hunk))
        val rows = DiffLayout.rows(hunk)
        if (split) DiffLayout.split(rows).forEach { add(DiffItem.Split(it)) } else rows.forEach { add(DiffItem.Unified(it)) }
    }
}

@Composable
private fun HunkHeader(hunk: DiffHunk, source: DiffSource, controller: GitDiffController, busy: Boolean) {
    val colors = editorColors
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.raised).padding(start = Spacing.m, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = hunk.header,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = EasyIdeFonts.mono,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (source == DiffSource.STAGED) {
            HunkAction(R.string.git_hunk_unstage, !busy) { controller.unstageHunk(hunk) }
        } else {
            HunkAction(R.string.git_hunk_stage, !busy) { controller.stageHunk(hunk) }
            HunkAction(R.string.git_hunk_discard, !busy) { controller.requestDiscardHunk(hunk) }
        }
    }
}

@Composable
private fun HunkAction(label: Int, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.minimumInteractiveComponentSize()) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(Spacing.l), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Message(text: String) = Centered {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = editorColors.textMuted)
}
