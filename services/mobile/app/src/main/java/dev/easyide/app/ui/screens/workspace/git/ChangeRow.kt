package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.kitPressPoint
import dev.easyide.app.ui.kit.rememberPressPoint
import dev.easyide.app.ui.screens.workspace.SourceControlCallbacks
import dev.easyide.app.ui.screens.workspace.files.FileIcon
import dev.easyide.app.ui.shell.DocumentOpener
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.diff.Comparison
import dev.easyide.sandbox.git.GitChange
import dev.easyide.sandbox.git.GitChangeType

/** The diff document a change opens: staged changes are HEAD against the index, the rest the index against the working tree. Null for a conflict, which opens the file, where the markers are. */
internal fun changeUri(change: GitChange): DocumentUri? = when {
    change.type == GitChangeType.CONFLICTED -> null
    change.staged -> Comparison.staged(change.path).uri
    else -> Comparison.unstaged(change.path).uri
}

/**
 * One change on one line (VS Code's): file icon, name, the directory in muted text, then at the end the
 * stage and discard (or unstage) actions while the row is [revealed] or hovered, and the status letter.
 * A tap opens its diff as a preview and reveals the row, a double tap keeps the diff, and a long press or
 * right click opens a menu under the press (open the changes, open them to the side, open the file, and
 * the same actions, so touch reaches them without a hover).
 */
@Composable
internal fun ChangeRow(change: GitChange, busy: Boolean, revealed: Boolean, onReveal: () -> Unit, callbacks: SourceControlCallbacks, opener: DocumentOpener) {
    val uri = changeUri(change)
    val press = rememberPressPoint()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    val open = { uri?.let(opener::preview) ?: callbacks.onOpenFile(change.path) }
    val paths = listOf(change.path)
    val discard = { callbacks.onDiscard(paths) }
    val toggleStage = { if (change.staged) callbacks.onUnstage(paths) else callbacks.onStage(paths) }
    val stageLabel = stringResource(if (change.staged) R.string.git_unstage else R.string.git_stage)
    val discardLabel = stringResource(R.string.git_discard)
    KitRow(
        title = change.name,
        subtitle = change.directory.ifEmpty { null },
        modifier = Modifier.hoverable(hover).kitPressPoint(press, onSecondary = { menuAt = it }),
        leading = { FileIcon(change.name, size = Kit.control.rowIcon) },
        onClick = { onReveal(); open() },
        selected = revealed,
        onDoubleClick = { uri?.let(opener::keep) ?: callbacks.onOpenFile(change.path) },
        onLongClick = { menuAt = press.at },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (revealed || hovered) {
                    if (!change.staged) KitIconButton(Icons.Filled.Undo, discardLabel, discard, enabled = !busy)
                    KitIconButton(if (change.staged) Icons.Filled.Remove else Icons.Filled.Add, stageLabel, toggleStage, enabled = !busy)
                }
                GitStatusLetter(change.type)
            }
        },
    )
    val items = buildList {
        if (uri != null) {
            add(KitMenuItem.Action(stringResource(R.string.shell_change_open_changes), { opener.preview(uri) }))
            add(KitMenuItem.Action(stringResource(R.string.wstage_open_beside), { opener.beside(uri) }))
        }
        add(KitMenuItem.Action(stringResource(R.string.shell_change_open_file), { callbacks.onOpenFile(change.path) }))
        add(KitMenuItem.Divider)
        add(KitMenuItem.Action(stageLabel, toggleStage, enabled = !busy))
        if (!change.staged) add(KitMenuItem.Action(discardLabel, discard, enabled = !busy, danger = true))
    }
    KitMenu(menuAt != null, { menuAt = null }, items, at = menuAt)
}
