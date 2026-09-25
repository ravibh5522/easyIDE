package dev.easyide.app.ui.shell.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.extensions.adapters.KeySurface
import dev.easyide.app.extensions.adapters.MenuEntry
import dev.easyide.app.ui.icons.iconFor
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.screens.workspace.KeyRowBar
import dev.easyide.app.ui.screens.workspace.ext.ContributedMenu
import dev.easyide.app.ui.screens.workspace.ext.sections
import dev.easyide.extensions.contrib.MenuIds

/**
 * The input dock (shell-model.md section 9): one flat row directly above the keyboard. For the editor
 * it is the key row with the contributed touch toolbar behind a `more` button at its end; for the
 * terminal, the shell keys. [DockRules] decides which of the two parts show. A read-only file has no
 * editing keys, so the dock is empty for it.
 */
@Composable
fun InputDock(env: WorkspaceEnv, focus: DockFocus, show: DockShow, editable: Boolean, runShortcut: (dev.easyide.app.ui.commands.KeyChord) -> Unit) {
    val contributions = env.contributions
    val line = Kit.hairline
    val border = Kit.colors.panelBorder
    Box(Modifier.kitTag("input-dock").drawBehind { drawRect(border, size = androidx.compose.ui.geometry.Size(size.width, line.toPx())) }) {
        when (focus) {
            DockFocus.EDITOR -> if (editable) {
                val keys = if (show.keys) contributions.keyRow(KeySurface.EDITOR)?.keys.orEmpty() else emptyList()
                val actions = if (show.actions) contributions.menu(MenuIds.EDITOR_TOUCH_TOOLBAR, env.commands) else emptyList()
                if (keys.isNotEmpty() || actions.isNotEmpty()) {
                    KeyRowBar(keys, onKey = { env.extensionHost.editorKey(it, runShortcut) }, trailing = { if (actions.isNotEmpty()) MoreActions(actions) { contributions.run(it) } })
                }
            }
            DockFocus.TERMINAL -> contributions.keyRow(KeySurface.TERMINAL)?.takeIf { show.keys }?.let { row ->
                KeyRowBar(row.keys, onKey = { action -> env.ui.activeTerminal?.session?.let { env.actions.onTerminalRowKey(action, it) } })
            }
            DockFocus.NONE -> Unit
        }
    }
}

/** The touch toolbar's entries as a menu, so the row stays one line however many an extension adds. */
@Composable
private fun RowScope.MoreActions(entries: List<MenuEntry>, onRun: (MenuEntry) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        KitIconButton(iconFor("more"), stringResource(R.string.dock_more_actions), { open = true })
        ContributedMenu(open, entries.sections(), onRun) { open = false }
    }
}
