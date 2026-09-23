package dev.easyide.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.easyide.app.AppContainer
import dev.easyide.app.ui.screens.home.HomeViewModel
import dev.easyide.app.ui.screens.newproject.NewProjectViewModel
import dev.easyide.app.ui.screens.settings.SettingsViewModel
import dev.easyide.app.ui.screens.workspace.WorkspaceViewModel

/**
 * Bridges the manually-wired [AppContainer] into ViewModel construction.
 * A generic reflective factory would be shorter but would fail at runtime on a
 * missing dependency; this fails at compile time instead.
 */
class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
        when (modelClass) {
            HomeViewModel::class.java -> HomeViewModel(
                projectManager = container.projectManager,
                environmentManager = container.environmentManager,
            )

            NewProjectViewModel::class.java -> NewProjectViewModel(
                projectManager = container.projectManager,
                environmentManager = container.environmentManager,
                externalFolderSync = container.externalFolderSync,
                defaultEnvironmentId = container.uiPreferences.defaultEnvironmentId,
                defaultProjectsFolderUri = container.uiPreferences.defaultProjectsFolderUri,
            )

            SettingsViewModel::class.java -> SettingsViewModel(
                uiPreferences = container.uiPreferences,
                settingsStore = container.settingsStore,
                environmentManager = container.environmentManager,
                externalFolderSync = container.externalFolderSync,
                projectManager = container.projectManager,
            )

            else -> error("Unknown ViewModel: ${modelClass.name}")
        } as T
}

/**
 * Workspace needs its project id at construction, which the shared factory
 * cannot supply - each project gets its own instance, keyed by id so switching
 * projects does not reuse another project's open tabs.
 */
class WorkspaceViewModelFactory(
    private val container: AppContainer,
    private val projectId: String,
    private val environmentId: String,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        require(modelClass == WorkspaceViewModel::class.java) {
            "WorkspaceViewModelFactory cannot create ${modelClass.name}"
        }
        return WorkspaceViewModel(
            projectId = projectId,
            environmentId = environmentId,
            projectFiles = container.projectFiles,
            linuxEnvironment = container.linuxEnvironment,
            environmentManager = container.environmentManager,
            projectManager = container.projectManager,
            appContext = container.appContext,
            gitService = container.gitService,
                    imageProvider = container::imageFor,
        ) as T
    }
}

@Composable
inline fun <reified T : ViewModel> appViewModel(factory: AppViewModelFactory): T =
    viewModel(factory = factory)
