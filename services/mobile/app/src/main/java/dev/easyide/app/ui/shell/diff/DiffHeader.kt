package dev.easyide.app.ui.shell.diff

import dev.easyide.app.ui.icons.iconFor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton

/** Where the reader is among the hunks, and how to move: a null step means there is no hunk that way. */
class HunkNav(val position: Int, val count: Int, val onPrevious: (() -> Unit)?, val onNext: (() -> Unit)?)

/** The words for one side of a comparison. */
@Composable
fun SideLabel.text(): String = when (this) {
    is SideLabel.Named -> text
    SideLabel.Index -> stringResource(R.string.shell_diff_side_index)
    SideLabel.Worktree -> stringResource(R.string.shell_diff_side_worktree)
    SideLabel.Nothing -> stringResource(R.string.shell_diff_side_nothing)
}

/**
 * The top of a diff document: the file (name over folder), what the two sides are, the line counts, and
 * hunk navigation. [onOpenFile] is null when the head side is not a file that exists to open.
 */
@Composable
internal fun DiffHeader(subject: DiffSubject, stats: Pair<Int, Int>?, nav: HunkNav?, onOpenFile: (() -> Unit)?) {
    val colors = Kit.colors
    val mono = Kit.text.monoSmall.copy(color = colors.textMuted)
    val directory = subject.path.substringBeforeLast('/', "")
    Column(Modifier.fillMaxWidth().background(colors.panel)) {
        Row(Modifier.padding(start = Kit.space.m, end = Kit.space.s), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = Kit.space.xs)) {
                BasicText(
                    subject.path.substringAfterLast('/'),
                    style = Kit.text.mono.copy(color = colors.plainText),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (directory.isNotEmpty()) BasicText(directory, style = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(stringResource(R.string.shell_diff_sides, subject.left.text(), subject.right.text()), style = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            stats?.let { (added, removed) -> BasicText(stringResource(R.string.git_diff_stats, added, removed), style = mono) }
            nav?.let { HunkControls(it) }
            onOpenFile?.let { KitIconButton(iconFor("open_in_new"), stringResource(R.string.git_diff_open_file), it) }
        }
        Box(Modifier.fillMaxWidth().height(Kit.hairline).background(colors.panelBorder))
    }
}

@Composable
private fun HunkControls(nav: HunkNav) {
    KitIconButton(iconFor("chevron_up"), stringResource(R.string.shell_diff_previous_hunk), { nav.onPrevious?.invoke() }, enabled = nav.onPrevious != null)
    if (nav.count > 0) {
        BasicText(
            stringResource(R.string.shell_diff_hunk_position, maxOf(nav.position, 0) + 1, nav.count),
            style = Kit.text.monoSmall.copy(color = Kit.colors.textMuted),
        )
    }
    KitIconButton(iconFor("chevron_down"), stringResource(R.string.shell_diff_next_hunk), { nav.onNext?.invoke() }, enabled = nav.onNext != null)
}
