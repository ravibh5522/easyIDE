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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.easyide.app.R
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitProgress
import dev.easyide.app.ui.kit.KitTabs
import dev.easyide.app.ui.screens.workspace.GitPanelState
import dev.easyide.app.ui.screens.workspace.SourceControlCallbacks
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
    val colors = Kit.colors

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
            Box(Modifier.fillMaxWidth().height(Kit.hairline).background(colors.panelBorder))
            Box(modifier = Modifier.fillMaxSize()) {
                DiffBody(diffState, git.diff, busy = state.busy)
            }
        }
    }
}

@Composable
private fun DiffToolbar(state: GitPanelState, diffState: GitDiffState, callbacks: SourceControlCallbacks) {
    val colors = Kit.colors
    val git = callbacks.git
    val status = state.status
    val path = diffState.path
    val hasStaged = status?.staged?.any { it.path == path } == true
    val hasUnstaged = status?.unstaged?.any { it.path == path } == true
    val name = path.substringAfterLast('/')
    val directory = path.substringBeforeLast('/', "")

    Column(modifier = Modifier.fillMaxWidth().background(colors.panel)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = Kit.space.s)) {
            KitIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.git_diff_close), git.diff::close)
            Column(modifier = Modifier.weight(1f)) {
                BasicText(name, style = Kit.text.mono.copy(color = colors.plainText), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (directory.isNotEmpty()) {
                    BasicText(directory, style = Kit.text.monoSmall.copy(color = colors.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            (diffState.diff as? FileDiff.Text)?.let { text ->
                BasicText(
                    text = stringResource(R.string.git_diff_stats, text.added, text.removed),
                    style = Kit.text.monoSmall.copy(color = colors.textMuted),
                )
            }
            KitIconButton(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.git_diff_open_file), { git.diff.close(); callbacks.onOpenFile(path) })
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Kit.space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Kit.space.xs),
        ) {
            if (hasStaged && hasUnstaged) {
                val sources = listOf(DiffSource.UNSTAGED, DiffSource.STAGED)
                KitTabs(
                    labels = listOf(stringResource(R.string.git_diff_unstaged), stringResource(R.string.git_diff_staged)),
                    selected = sources.indexOf(diffState.source),
                    onSelect = { git.diff.showSource(sources[it]) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                BasicText(
                    text = stringResource(if (diffState.source == DiffSource.STAGED) R.string.git_diff_staged else R.string.git_diff_unstaged),
                    style = Kit.text.title.copy(color = colors.textMuted),
                    modifier = Modifier.weight(1f).padding(horizontal = Kit.space.s),
                )
            }
            if (diffState.source == DiffSource.STAGED) {
                KitButton(stringResource(R.string.git_diff_unstage_file), { callbacks.onUnstage(listOf(path)) }, style = KitButtonStyle.Ghost, enabled = !state.busy)
            } else {
                KitButton(stringResource(R.string.git_diff_stage_file), { callbacks.onStage(listOf(path)) }, style = KitButtonStyle.Ghost, enabled = !state.busy)
            }
        }
    }
}

@Composable
private fun DiffBody(diffState: GitDiffState, controller: GitDiffController, busy: Boolean) {
    val colors = Kit.colors
    when (val diff = diffState.diff) {
        null -> Centered {
            if (diffState.error != null) {
                BasicText(diffState.error, style = Kit.text.body.copy(color = colors.error))
            } else {
                KitProgress(fraction = null)
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
    val colors = Kit.colors

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
    val colors = Kit.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.raised).padding(start = Kit.space.m, end = Kit.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = hunk.header,
            style = Kit.text.monoSmall.copy(color = colors.textMuted),
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
    KitButton(stringResource(label), onClick, style = KitButtonStyle.Ghost, enabled = enabled)
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(Kit.space.l), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Message(text: String) = Centered {
    BasicText(text, style = Kit.text.body.copy(color = Kit.colors.textMuted))
}
