package dev.easyide.app.ui.screens.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.easyide.sandbox.EnvironmentManager
import dev.easyide.sandbox.ProjectManager
import dev.easyide.sandbox.files.ProjectFiles
import dev.easyide.sandbox.model.EnvironmentState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DemoUiState(
    /** A Linux environment is installed; the demos need one, so they wait (the install bar shows why) until it is. */
    val linuxReady: Boolean = false,
    val busy: Boolean = false,
    /** Set once the demo project exists; the screen navigates to it and the VM is done. */
    val openProjectId: String? = null,
    val failed: Boolean = false,
)

/** Creates a demo's one-file project and queues its run for the workspace. Reuses the project of an earlier demo run. */
class DemoViewModel(
    private val projectManager: ProjectManager,
    private val environmentManager: EnvironmentManager,
    private val projectFiles: ProjectFiles,
    private val handoff: DemoHandoff,
) : ViewModel() {

    private val progress = MutableStateFlow(DemoUiState())

    private val ready: StateFlow<Boolean> = environmentManager.environments
        .map { all -> all.any { it.state == EnvironmentState.READY } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val state: StateFlow<DemoUiState> = combine(progress, ready) { p, r -> p.copy(linuxReady = r) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DemoUiState())

    fun start(demo: DemoSpec) {
        if (progress.value.busy) return
        progress.value = DemoUiState(busy = true)
        viewModelScope.launch {
            val projectId = createProject(demo)
            if (projectId == null) {
                progress.value = DemoUiState(failed = true)
                return@launch
            }
            handoff.put(projectId, DemoRun(demo.file, demo.command))
            progress.value = DemoUiState(openProjectId = projectId)
        }
    }

    private suspend fun createProject(demo: DemoSpec): String? {
        val environmentId = environmentManager.environments.first().firstOrNull { it.state == EnvironmentState.READY }?.id ?: return null
        val existing = projectManager.projects.first().firstOrNull { it.name == demo.projectName && it.environmentId == environmentId }
        val projectId = existing?.id
            ?: projectManager.create(demo.projectName, environmentId, seedStarterFiles = false).getOrNull()?.id
            ?: return null
        return projectId.takeIf { projectFiles.writeText(it, demo.file, demo.source).isSuccess }
    }
}
