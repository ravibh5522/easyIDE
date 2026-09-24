package dev.easyide.app.ui.shell.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.easyide.app.extensions.adapters.KeySurface
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.screens.workspace.KeyRowBar
import dev.easyide.app.ui.screens.workspace.ext.InputModeState
import dev.easyide.app.ui.screens.workspace.ext.TouchToolbar
import dev.easyide.extensions.contrib.MenuIds

/**
 * The input dock (shell-model.md section 9): the contributed touch toolbar and key row for the
 * editor, the shell keys for the terminal, in one surface directly above the keyboard. It is today's
 * key rows and toolbar, unchanged; what moved is where they sit and when they show ([DockRules]).
 * A read-only file has no editing keys, so the dock is empty for it.
 */
@Composable
fun InputDock(env: WorkspaceEnv, focus: DockFocus, editable: Boolean, inputMode: InputModeState, runShortcut: (dev.easyide.app.ui.commands.KeyChord) -> Unit) {
    val contributions = env.contributions
    Column(Modifier.fillMaxWidth().kitTag("input-dock").background(Kit.colors.panel)) {
        when (focus) {
            DockFocus.EDITOR -> if (editable) {
                if (inputMode.mode == InputModeState.TOUCH) TouchToolbar(contributions.menu(MenuIds.EDITOR_TOUCH_TOOLBAR, env.commands)) { contributions.run(it) }
                contributions.keyRow(KeySurface.EDITOR)?.let { row -> KeyRowBar(row.keys, onKey = { env.extensionHost.editorKey(it, runShortcut) }) }
            }
            DockFocus.TERMINAL -> contributions.keyRow(KeySurface.TERMINAL)?.let { row ->
                KeyRowBar(row.keys, onKey = { action -> env.ui.activeTerminal?.session?.let { env.actions.onTerminalRowKey(action, it) } })
            }
            DockFocus.NONE -> Unit
        }
    }
}
