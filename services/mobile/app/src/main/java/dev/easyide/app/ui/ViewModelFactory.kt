package dev.easyide.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.easyide.app.AppContainer
import dev.easyide.app.data.settings.WorkspaceSettingsSchema
import dev.easyide.app.diagnostics.Cleanup
import dev.easyide.app.diagnostics.DiagnosticsCollector
import dev.easyide.app.diagnostics.StorageLocations
import dev.easyide.app.diagnostics.readUname
import dev.easyide.app.ui.screens.diagnostics.DiagnosticsViewModel
import dev.easyide.app.ui.screens.extensions.ExtensionsViewModel
import dev.easyide.app.ui.screens.home.HomeViewModel
import dev.easyide.app.ui.screens.newproject.NewProjectViewModel
import dev.easyide.app.ui.screens.onboarding.EnvironmentSetupViewModel
import dev.easyide.app.ui.screens.settings.SettingsViewModel
import dev.easyide.app.ui.screens.workspace.WorkspaceViewModel
import kotlinx.coroutines.Job
import java.io.File
import kotlinx.coroutines.flow.first

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
                projectFiles = container.projectFiles,
                gitService = container.gitService,
                externalFolderSync = container.externalFolderSync,
                cloner = container.gitRemote::clone,
                defaultEnvironmentId = container.uiPreferences.defaultEnvironmentId,
            )

            EnvironmentSetupViewModel::class.java -> EnvironmentSetupViewModel(
                environmentManager = container.environmentManager,
                linuxEnvironment = container.linuxEnvironment,
                keepAlive = container.installKeepAlive,
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
                profileManager = container.profileManager,
                safeModeState = container.safeMode,
                projectTrust = container.projectTrust,
                transfer = container.settingsTransfer,
                environmentManager = container.environmentManager,
                externalFolderSync = container.externalFolderSync,
                projectManager = container.projectManager,
                themes = container.extensions.themes,
                contributions = container.extensions.runtime.contributions,
                lspServers = container.lsp.servers,
            )

            ExtensionsViewModel::class.java -> ExtensionsViewModel(
                appContext = container.appContext,
                extensions = container.extensions,
                settingsStore = container.settingsStore,
                safeMode = container.safeMode,
                environmentManager = container.environmentManager,
                projectManager = container.projectManager,
                projectRoot = container.projectFiles::projectRoot,
            )

            DiagnosticsViewModel::class.java -> DiagnosticsViewModel(
                collector = DiagnosticsCollector(
                    build = container.buildInfo,
                    uname = ::readUname,
                    paths = container.paths,
                    environments = container.environmentManager.environments,
                    nativeLibraryDir = File(container.appContext.applicationInfo.nativeLibraryDir),
                    storage = StorageLocations(logs = container.logDir, sessionBackups = container.sessionsDir),
                    appLog = container.appLog,
                    crashReports = container.crashReports,
                ),
                appLog = container.appLog,
                crashReports = container.crashReports,
                cleanup = Cleanup(container.paths, container.appLog, container.crashReports),
                resolver = container.appContext.contentResolver,
            )

            else -> error("Unknown ViewModel: ${modelClass.name}")
        } as T
}

/**
 * Workspace needs its project id at construction, which the shared factory
 * cannot supply - each project gets its own instance, made by the app-scoped
 * [dev.easyide.app.session.WorkspaceRegistry] (one per project id, so switching
 * projects does not reuse another project's open tabs).
 *
 * [settled] is the project's previous session while it is still saving on its
 * way out; the new one restores only after it.
 */
class WorkspaceViewModelFactory(
    private val container: AppContainer,
    private val projectId: String,
    private val environmentId: String,
    private val settled: Job?,
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
            lspRuntime = container.lsp,
            extensions = container.extensions,
            sessionStore = container.sessionStore,
            restoreOpenTabs = { container.settingsStore.snapshot.first()[WorkspaceSettingsSchema.restoreOpenTabs] },
            settled = settled,
            log = container.appLog,
        ) as T
    }
}

@Composable
inline fun <reified T : ViewModel> appViewModel(factory: AppViewModelFactory): T =
    viewModel(factory = factory)
