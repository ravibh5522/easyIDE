package dev.easyide.app.ui.shell.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.ui.appViewModel
import dev.easyide.app.ui.screens.home.HomeCallbacks
import dev.easyide.app.ui.screens.home.HomeDialogHost
import dev.easyide.app.ui.screens.home.HomeNowPage
import dev.easyide.app.ui.screens.home.HomePanel
import dev.easyide.app.ui.screens.home.HomeUiState
import dev.easyide.app.ui.screens.home.HomeViewModel
import dev.easyide.app.ui.screens.home.ProjectPage
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.DocumentUri

/*
 * Home on the shell: the project list as the primary panel, the "Now" page as the stage's default
 * document, a project as the document `easyide://project/<id>`. Every piece reads the one
 * HomeViewModel of the entry, so a change in one shows in all.
 */

@Composable
private fun homeState(deps: ShellDeps): HomeUiState {
    val viewModel: HomeViewModel = appViewModel(deps.factory)
    return viewModel.uiState.collectAsStateWithLifecycle().value
}

/** Home's state with its callbacks wired to the view model, the exits and the shell. */
@Composable
private fun HomeSlot(deps: ShellDeps, content: @Composable (HomeUiState, HomeCallbacks) -> Unit) {
    val viewModel: HomeViewModel = appViewModel(deps.factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val shell = LocalShellActions.current
    val callbacks = remember(viewModel, deps, shell) { homeCallbacks(viewModel, deps, shell) }
    content(state, callbacks)
}

private fun homeCallbacks(viewModel: HomeViewModel, deps: ShellDeps, shell: ShellActions) = HomeCallbacks(
    onQueryChanged = viewModel::onQueryChanged,
    onSortChanged = viewModel::onSortChanged,
    onSelect = viewModel::onSelect,
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
    onOpenProjectPage = { id -> DocumentUri.easyide("project", id)?.let(shell.open) },
    onOpenDocument = { text -> DocumentUri.parse(text)?.let(shell.open) },
    onStop = viewModel::onStop,
)

/** The primary panel; the "Now" sections live in it only where there is no stage beside it (a phone). */
internal fun homePanel(deps: ShellDeps) = panelRenderer { modifier ->
    val shellState = LocalShellState.current
    HomeSlot(deps) { state, callbacks ->
        HomePanel(
            state, deps.container.externalFolderSync, callbacks, modifier,
            selectedProjectId = AppDocuments.projectIdOf(shellState.activeDocument),
            showNow = shellState.compact,
        )
    }
}

/** What the stage shows on a wide window while no project page is open. */
internal fun homeNow(deps: ShellDeps) = panelRenderer { modifier ->
    HomeSlot(deps) { state, callbacks -> HomeNowPage(state, callbacks, modifier) }
}

internal fun projectDocument(deps: ShellDeps) = documentRenderer(
    title = { uri -> AppDocuments.projectIdOf(uri)?.let { homeState(deps).find(it)?.project?.name } },
) { uri, modifier ->
    HomeSlot(deps) { state, callbacks -> ProjectPage(AppDocuments.projectIdOf(uri).orEmpty(), state, callbacks, modifier) }
}

/** Home's dialogs, mounted once: rename, delete, clone and the rest ask their question here whichever piece was tapped. */
@Composable
internal fun HomeDialogs(deps: ShellDeps) {
    HomeSlot(deps) { state, callbacks -> HomeDialogHost(state, callbacks) }
}
