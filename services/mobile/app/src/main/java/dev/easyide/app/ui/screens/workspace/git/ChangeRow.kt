package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Undo
import androidx.compose.foundation.text.BasicText
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
 * One change: a tap opens its diff as a preview, a double tap keeps it, and a long press or right click
 * opens a menu under the press (open the changes, open them to the side, open the file). At most two
 * trailing actions (discard and stage, or unstage) and the status letter.
 */
@Composable
internal fun ChangeRow(change: GitChange, busy: Boolean, callbacks: SourceControlCallbacks, opener: DocumentOpener) {
    val colors = Kit.colors
    val uri = changeUri(change)
    val press = rememberPressPoint()
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    val open = { uri?.let(opener::preview) ?: callbacks.onOpenFile(change.path) }
    KitRow(
        title = change.name,
        subtitle = change.directory.ifEmpty { null },
        modifier = Modifier.kitPressPoint(press, onSecondary = { menuAt = it }),
        mono = true,
        onClick = open,
        onDoubleClick = { uri?.let(opener::keep) ?: callbacks.onOpenFile(change.path) },
        onLongClick = { menuAt = press.at },
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
                    style = Kit.text.monoSmall.copy(color = change.type.tint(colors.git)),
                )
            }
        },
    )
    val items = buildList {
        if (uri != null) {
            add(KitMenuItem.Action(stringResource(R.string.shell_change_open_changes), { opener.preview(uri) }))
            add(KitMenuItem.Action(stringResource(R.string.wstage_open_beside), { opener.beside(uri) }))
        }
        add(KitMenuItem.Action(stringResource(R.string.shell_change_open_file), { callbacks.onOpenFile(change.path) }))
    }
    KitMenu(menuAt != null, { menuAt = null }, items, at = menuAt)
}
