package dev.easyide.app.ui.shell.host

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import dev.easyide.app.R
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.EditorGroup

private fun TabAction.label(): Int = when (this) {
    TabAction.OPEN_BESIDE -> R.string.wstage_open_beside
    TabAction.MOVE_NEXT -> R.string.wstage_move_next
    TabAction.KEEP -> R.string.wstage_keep_open
    TabAction.PIN -> R.string.wstage_pin
    TabAction.UNPIN -> R.string.wstage_unpin
    TabAction.CLOSE -> R.string.wstage_close
    TabAction.CLOSE_OTHERS -> R.string.wstage_close_others
    TabAction.CLOSE_RIGHT -> R.string.wstage_close_right
    TabAction.CLOSE_ALL -> R.string.wstage_close_all
}

/**
 * The menu of one document's tab ([TabMenuPlan]), under [at] when a press opened it. It draws nothing while
 * the stage has no tab menu, so a scope that does not wire one shows no dead entries.
 */
@Composable
internal fun TabMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    group: EditorGroup,
    uri: DocumentUri,
    callbacks: GroupCallbacks,
    context: TabMenuContext,
    at: IntOffset? = null,
) {
    if (!expanded || !callbacks.hasTabMenu) return
    val items = TabMenuPlan.of(group, uri, context).flatMapIndexed { i, section ->
        val actions = section.map { KitMenuItem.Action(stringResource(it.label()), { callbacks.tabAction(uri, it) }) }
        if (i > 0) listOf(KitMenuItem.Divider) + actions else actions
    }
    KitMenu(true, onDismiss, items, at = at)
}
