package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.app.ui.shell.ContainerRegistry
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.NavEnv
import dev.easyide.app.ui.shell.NavItem
import dev.easyide.app.ui.shell.NavPrefs
import dev.easyide.app.ui.shell.NavRegistry
import dev.easyide.app.ui.shell.NavTarget
import dev.easyide.app.ui.shell.ShellScope

/** The navigation items of workspace scope: which of the registry's items the surface shows. */
object WorkspaceNav {
    /** The commands a workspace navigation item may run: the palette, and the two ways out of the project. */
    val COMMANDS: Set<String> = setOf(CommandIds.SHOW_COMMANDS, CoreShell.BACK_TO_PROJECTS_COMMAND, CoreShell.CLOSE_PROJECT_COMMAND)

    /** The items in display order: an item whose container is not registered, or whose command is unknown, is dropped. */
    fun items(navigation: NavRegistry, containers: ContainerRegistry, prefs: NavPrefs, holds: (String) -> Boolean): List<NavItem> =
        navigation.visible(ShellScope.WORKSPACE, prefs, NavEnv(holds) { target ->
            when (target) {
                is NavTarget.Container -> containers.byId(target.id) != null
                is NavTarget.Command -> target.id in COMMANDS
            }
        })
}
