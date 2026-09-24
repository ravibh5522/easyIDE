package dev.easyide.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.easyide.app.AppContainer
import dev.easyide.app.ui.AppViewModelFactory
import dev.easyide.app.ui.WorkspaceViewModelFactory
import dev.easyide.app.ui.appViewModel
import dev.easyide.app.ui.foundation.NavTransitions
import dev.easyide.app.ui.screens.home.HomeScreen
import dev.easyide.app.ui.screens.home.HomeViewModel
import dev.easyide.app.ui.screens.newproject.NewProjectScreen
import dev.easyide.app.ui.screens.newproject.NewProjectViewModel
import dev.easyide.app.ui.screens.onboarding.OnboardingScreen
import dev.easyide.app.ui.screens.settings.ProjectSettingsScope
import dev.easyide.app.ui.screens.settings.SettingsScreen
import dev.easyide.app.ui.screens.settings.SettingsViewModel
import dev.easyide.app.ui.screens.workspace.ProjectNotFound
import dev.easyide.app.ui.screens.workspace.WorkspaceLoading
import dev.easyide.app.ui.screens.workspace.WorkspaceScreen
import dev.easyide.app.ui.screens.workspace.WorkspaceViewModel

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
    onOnboardingComplete: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val transitions = NavTransitions(motionEnabled)

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
                onContinue = {
                    onOnboardingComplete()
                    navController.navigate(Destination.Home.route) {
                        popUpTo(Destination.Onboarding.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Destination.Home.route) {
            val viewModel: HomeViewModel = appViewModel(viewModelFactory)
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            HomeScreen(
                uiState = uiState,
                onOpenProject = { item ->
                    viewModel.onProjectOpened(item.project.id)
                    navController.navigate(Destination.Workspace.routeFor(item.project.id))
                },
                onNewProject = { navController.navigate(Destination.NewProject.route) },
                onOpenSettings = { navController.navigate(Destination.Settings.route) },
            )
        }

        composable(Destination.Workspace.route) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString(Destination.Workspace.ARG_PROJECT_ID).orEmpty()
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
            val workspaceViewModel: WorkspaceViewModel = viewModel(
                key = projectId,
                factory = WorkspaceViewModelFactory(container, projectId, environmentId),
            )
            val uiState by workspaceViewModel.uiState.collectAsStateWithLifecycle()
            val gitState by workspaceViewModel.gitState.collectAsStateWithLifecycle()

            ProjectSettingsScope(container, projectId, environmentId) {
            WorkspaceScreen(
                projectName = project.name,
                uiState = uiState,
                gitState = gitState,
                decorations = workspaceViewModel.decorations,
                lsp = workspaceViewModel.lsp,
                gitCallbacks = dev.easyide.app.ui.screens.workspace.SourceControlCallbacks(
                    onMessageChanged = workspaceViewModel::onGitMessageChanged,
                    onCommit = workspaceViewModel::commitGit,
                    onStage = workspaceViewModel::stageGit,
                    onUnstage = workspaceViewModel::unstageGit,
                    onDiscard = workspaceViewModel::discardGit,
                    onInitRepository = workspaceViewModel::initGitRepository,
                    onRefresh = workspaceViewModel::refreshGit,
                    onOpenFile = workspaceViewModel::openFileByPath,
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

        composable(Destination.Settings.route) {
            val viewModel: SettingsViewModel = appViewModel(viewModelFactory)
            SettingsScreen(
                viewModel = viewModel,
                externalFolderSync = container.externalFolderSync,
                onBack = { navController.popBackStack() },
            )
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
