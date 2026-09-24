package dev.easyide.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import dev.easyide.app.AppContainer
import dev.easyide.app.ui.AppViewModelFactory
import dev.easyide.app.ui.appViewModel
import dev.easyide.app.ui.foundation.NavTransitions
import dev.easyide.app.ui.screens.diagnostics.DiagnosticsScreen
import dev.easyide.app.ui.screens.diagnostics.DiagnosticsViewModel
import dev.easyide.app.ui.screens.newproject.NewProjectScreen
import dev.easyide.app.ui.screens.newproject.NewProjectViewModel
import dev.easyide.app.ui.screens.onboarding.InstallLinuxScreen
import dev.easyide.app.ui.screens.onboarding.OnboardingScreen
import dev.easyide.app.ui.screens.settings.ProjectSettingsScope
import dev.easyide.app.ui.screens.workspace.ProjectNotFound
import dev.easyide.app.ui.screens.workspace.WorkspaceLoading
import dev.easyide.app.ui.screens.workspace.WorkspaceScreen
import dev.easyide.app.ui.screens.workspace.files.LocalIgnoreIndex
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.host.AppRenderers
import dev.easyide.app.ui.shell.host.ShellDeps
import dev.easyide.app.ui.shell.host.ShellExits
import dev.easyide.app.ui.shell.host.ShellHost
import dev.easyide.app.ui.shell.host.ShellViewModel

/**
 * Top-level nav graph. Home is the stack root; everything else is one level
 * deep - see docs/ui-shell/arch.md "Navigation flow".
 */
@Composable
fun AppNavHost(
    startAtOnboarding: Boolean,
    motionEnabled: Boolean,
    container: AppContainer,
    viewModelFactory: AppViewModelFactory,
    shell: ShellViewModel,
    onOnboardingComplete: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val transitions = NavTransitions(motionEnabled)
    LaunchRedirect(container, navController, startAtOnboarding)
    CrashRecovery(container, navController, startAtOnboarding)

    NavHost(
        navController = navController,
        startDestination = if (startAtOnboarding) Destination.Onboarding.route else Destination.Home.route,
        enterTransition = transitions.enter(),
        exitTransition = transitions.exit(),
        popEnterTransition = transitions.popEnter(),
        popExitTransition = transitions.popExit(),
    ) {
        composable(Destination.Onboarding.route) {
            OnboardingScreen(
                setup = appViewModel(viewModelFactory),
                onFinished = {
                    onOnboardingComplete()
                    navController.navigate(Destination.Home.route) {
                        popUpTo(Destination.Onboarding.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Destination.InstallLinux.route) {
            InstallLinuxScreen(setup = appViewModel(viewModelFactory), onBack = { navController.popBackStack() })
        }

        composable(Destination.Home.route) {
            val deps = remember(container, viewModelFactory) {
                ShellDeps(
                    container, viewModelFactory,
                    ShellExits(
                        onOpenProject = { id, withTerminal -> navController.navigate(Destination.Workspace.routeFor(id, withTerminal)) },
                        onNewProject = { navController.navigate(Destination.NewProject.route) },
                        onInstallLinux = { navController.navigate(Destination.InstallLinux.route) },
                        onOpenDiagnostics = { navController.navigate(Destination.Diagnostics.route) },
                    ),
                )
            }
            ShellHost(shell, remember(deps) { AppRenderers.panels(deps) }, remember { AppRenderers.documents() })
        }

        composable(
            route = Destination.Workspace.route,
            arguments = listOf(
                navArgument(Destination.Workspace.ARG_OPEN_TERMINAL) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString(Destination.Workspace.ARG_PROJECT_ID).orEmpty()
            val openTerminal = backStackEntry.arguments?.getBoolean(Destination.Workspace.ARG_OPEN_TERMINAL) == true
            // Straight from the repository rather than a second HomeViewModel:
            // null means the store has not emitted yet, which is distinct from
            // "loaded, and no such project".
            val projects by container.projectManager.projects.collectAsStateWithLifecycle(initialValue = null)
            val project = projects?.find { it.id == projectId }

            // Wait for the project record: its environment id decides which
            // rootfs the terminal runs in, and guessing would start the wrong one.
            if (projects == null) {
                WorkspaceLoading()
            } else if (project == null) {
                ProjectNotFound(onBackHome = { navController.popBackStack(Destination.Home.route, inclusive = false) })
            } else {
            val environmentId = project.environmentId
            val workspaceViewModel = rememberWorkspace(container, projectId, environmentId) {
                navController.popBackStack(Destination.Home.route, inclusive = false)
            }
            val uiState by workspaceViewModel.uiState.collectAsStateWithLifecycle()
            val gitState by workspaceViewModel.gitState.collectAsStateWithLifecycle()
            LaunchedEffect(projectId) { if (openTerminal) workspaceViewModel.revealTerminal() }

            val ignoreIndex by workspaceViewModel.editing.files.ignore.collectAsStateWithLifecycle()
            ProjectSettingsScope(container, projectId, environmentId) {
            CompositionLocalProvider(LocalIgnoreIndex provides ignoreIndex) {
            WorkspaceScreen(
                projectName = project.name,
                uiState = uiState,
                gitState = gitState,
                decorations = workspaceViewModel.decorations,
                lsp = workspaceViewModel.lsp,
                extensionHost = workspaceViewModel.extensionHost,
                extensions = container.extensions,
                selections = workspaceViewModel.selections,
                session = workspaceViewModel.sessionUi,
                onCloseProject = { container.workspaces.close(projectId) },
                editing = workspaceViewModel.editing,
                onOpenExtensions = { shell.goTo(CoreShell.EXTENSIONS); navController.popBackStack(Destination.Home.route, inclusive = false) },
                onOpenSettings = { shell.goTo(CoreShell.SETTINGS); navController.popBackStack(Destination.Home.route, inclusive = false) },
                gitCallbacks = dev.easyide.app.ui.screens.workspace.SourceControlCallbacks(
                    onMessageChanged = workspaceViewModel::onGitMessageChanged,
                    onCommit = workspaceViewModel::commitGit,
                    onStage = workspaceViewModel::stageGit,
                    onUnstage = workspaceViewModel::unstageGit,
                    onDiscard = workspaceViewModel::discardGit,
                    onInitRepository = workspaceViewModel::initGitRepository,
                    onRefresh = workspaceViewModel::refreshGit,
                    onOpenFile = workspaceViewModel::openFileByPath,
                    git = workspaceViewModel.gitControllers,
                ),
                callbacks = dev.easyide.app.ui.screens.workspace.WorkspaceCallbacks(
                    onFileOpened = workspaceViewModel::onFileOpened,
                    onDirectoryToggled = workspaceViewModel::onDirectoryToggled,
                    onRefreshTree = workspaceViewModel::refreshTree,
                    onTabSelected = workspaceViewModel::onTabSelected,
                    onTabClosed = workspaceViewModel::onTabClosed,
                    onContentChanged = workspaceViewModel::onContentChanged,
                    onTogglePreview = workspaceViewModel::onTogglePreview,
                    onSave = workspaceViewModel::onSaveActiveTab,
                    onSaveTabs = workspaceViewModel::onSaveTabs,
                    onCreateFile = workspaceViewModel::onCreateFile,
                    onCreateFolder = workspaceViewModel::onCreateFolder,
                    onRename = workspaceViewModel::onRename,
                    onDelete = workspaceViewModel::onDelete,
                    onCopyToClipboard = workspaceViewModel::onCopyToClipboard,
                    onPaste = workspaceViewModel::onPaste,
                    absolutePathOf = workspaceViewModel::absolutePathOf,
                    onNewTerminal = workspaceViewModel::onNewTerminal,
                    onSelectTerminal = workspaceViewModel::onSelectTerminal,
                    onCloseTerminal = workspaceViewModel::onCloseTerminal,
                    onRenameTerminal = workspaceViewModel::onRenameTerminal,
                    onInstallLinux = workspaceViewModel::onInstallLinux,
                    onStatusShown = workspaceViewModel::onStatusShown,
                    onBack = { navController.popBackStack() },
                ),
            )
            }
            }
            }
        }

        composable(Destination.Diagnostics.route) {
            val viewModel: DiagnosticsViewModel = appViewModel(viewModelFactory)
            DiagnosticsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(Destination.NewProject.route) {
            val viewModel: NewProjectViewModel = appViewModel(viewModelFactory)
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            // Navigate onward only once creation actually succeeded, so a
            // failure keeps the user on the form with the error visible.
            LaunchedEffect(uiState.createdProjectId) {
                val id = uiState.createdProjectId ?: return@LaunchedEffect
                navController.navigate(Destination.Workspace.routeFor(id)) {
                    popUpTo(Destination.NewProject.route) { inclusive = true }
                }
            }

            NewProjectScreen(
                uiState = uiState,
                externalFolderSync = container.externalFolderSync,
                onProjectNameChanged = viewModel::onProjectNameChanged,
                onChoiceChanged = viewModel::onChoiceChanged,
                onEnvironmentSelected = viewModel::onEnvironmentSelected,
                onNewEnvironmentLabelChanged = viewModel::onNewEnvironmentLabelChanged,
                onBackendSelected = viewModel::onBackendSelected,
                onImageSelected = viewModel::onImageSelected,
                onExternalFolderChosen = viewModel::onExternalFolderChosen,
                onExternalFolderCleared = viewModel::onExternalFolderCleared,
                onExternalFolderPickFailed = viewModel::onExternalFolderPickFailed,
                onSubmit = viewModel::submit,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
