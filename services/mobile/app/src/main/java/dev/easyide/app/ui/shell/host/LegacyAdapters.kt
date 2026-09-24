package dev.easyide.app.ui.shell.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.AppContainer
import dev.easyide.app.ui.AppViewModelFactory
import dev.easyide.app.ui.appViewModel
import dev.easyide.app.ui.screens.extensions.ExtensionsScreen
import dev.easyide.app.ui.screens.extensions.ExtensionsViewModel
import dev.easyide.app.ui.screens.home.HomeCallbacks
import dev.easyide.app.ui.screens.home.HomeScreen
import dev.easyide.app.ui.screens.home.HomeViewModel
import dev.easyide.app.ui.screens.settings.SettingsScreen
import dev.easyide.app.ui.screens.settings.SettingsViewModel
import dev.easyide.app.ui.shell.CoreShell

/*
 * TEMPORARY (R1): adapters that host the pre-shell Home, Settings and Extensions screens as
 * window-spanning panels, so the app keeps working until the HOME, SETTINGS and EXTENSIONS
 * branches land their panel and page composables. When they do, replace `legacyPanels` in
 * AppRenderers with real bindings, delete this file and `PanelBinding.spansWindow`
 * (docs/ui-redesign/tracker.md, R1 "Swap list").
 */

/** What the app shell cannot do itself: leave for a route outside it. */
class ShellExits(
    val onOpenProject: (projectId: String, withTerminal: Boolean) -> Unit,
    val onNewProject: () -> Unit,
    val onInstallLinux: () -> Unit,
    val onOpenDiagnostics: () -> Unit,
)

class ShellDeps(val container: AppContainer, val factory: AppViewModelFactory, val exits: ShellExits)

fun legacyPanels(deps: ShellDeps): PanelRendererRegistry = PanelRendererRegistry(
    mapOf(
        CoreShell.HOME_PROJECTS to PanelBinding(spanning { LegacyHome(deps) }, spansWindow = true),
        CoreShell.SETTINGS_CATEGORIES to PanelBinding(spanning { LegacySettings(deps) }, spansWindow = true),
        CoreShell.EXTENSIONS_LIST to PanelBinding(spanning { LegacyExtensions(deps) }, spansWindow = true),
    ),
)

private fun spanning(content: @Composable () -> Unit): PanelRenderer = object : PanelRenderer {
    @Composable
    override fun Render(modifier: Modifier) = content()
}

@Composable
private fun LegacyHome(deps: ShellDeps) {
    val viewModel: HomeViewModel = appViewModel(deps.factory)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val shell = LocalShellActions.current
    HomeScreen(
        uiState = uiState,
        externalFolderSync = deps.container.externalFolderSync,
        callbacks = HomeCallbacks(
            onQueryChanged = viewModel::onQueryChanged,
            onSortChanged = viewModel::onSortChanged,
            onSelect = viewModel::onSelect,
            onCloseDetail = viewModel::onCloseDetail,
            onResumed = viewModel::onResumed,
            onOpenProject = { item, withTerminal ->
                viewModel.onProjectOpened(item.project.id)
                deps.exits.onOpenProject(item.project.id, withTerminal)
            },
            onNewProject = deps.exits.onNewProject,
            onOpenSettings = { shell.goTo(CoreShell.SETTINGS) },
            onInstallLinux = deps.exits.onInstallLinux,
            onDialog = viewModel::onDialog,
            onFolderPicked = viewModel::onFolderPicked,
            onFolderPickFailed = viewModel::onFolderPickFailed,
            onMessageShown = viewModel::onMessageShown,
            rename = viewModel::rename,
            duplicate = viewModel::duplicate,
            delete = viewModel::delete,
            changeEnvironment = viewModel::changeEnvironment,
            importFolder = viewModel::importFolder,
            clone = viewModel::clone,
        ),
    )
}

@Composable
private fun LegacySettings(deps: ShellDeps) {
    val viewModel: SettingsViewModel = appViewModel(deps.factory)
    val shell = LocalShellActions.current
    SettingsScreen(
        viewModel = viewModel,
        externalFolderSync = deps.container.externalFolderSync,
        onOpenExtensions = { shell.goTo(CoreShell.EXTENSIONS) },
        onOpenDiagnostics = deps.exits.onOpenDiagnostics,
        onBack = { shell.goTo(CoreShell.HOME) },
    )
}

@Composable
private fun LegacyExtensions(deps: ShellDeps) {
    val viewModel: ExtensionsViewModel = appViewModel(deps.factory)
    val shell = LocalShellActions.current
    ExtensionsScreen(viewModel = viewModel, onBack = { shell.goTo(CoreShell.HOME) })
}
