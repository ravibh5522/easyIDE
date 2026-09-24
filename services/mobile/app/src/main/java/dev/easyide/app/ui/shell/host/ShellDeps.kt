package dev.easyide.app.ui.shell.host

import dev.easyide.app.AppContainer
import dev.easyide.app.ui.AppViewModelFactory

/** What the app shell cannot do itself: leave for a route outside it. */
class ShellExits(
    val onOpenProject: (projectId: String, withTerminal: Boolean) -> Unit,
    val onNewProject: () -> Unit,
    val onInstallLinux: () -> Unit,
    val onOpenDiagnostics: () -> Unit,
)

/** What the bound panels and pages draw on: the app's services, the view-model factory, the exits, and the registries the layout page lists. */
class ShellDeps(val container: AppContainer, val factory: AppViewModelFactory, val exits: ShellExits, val registries: AppRegistries)
