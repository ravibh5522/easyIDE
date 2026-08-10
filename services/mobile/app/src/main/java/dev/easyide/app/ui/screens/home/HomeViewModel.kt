package dev.easyide.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.easyide.sandbox.EnvironmentManager
import dev.easyide.sandbox.ProjectManager
import dev.easyide.sandbox.model.ProjectRecord
import dev.easyide.sandbox.model.SandboxEnvironment
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home shows projects together with the environment each one runs in, so a
 * shared environment is visible at a glance rather than buried in settings.
 */
data class ProjectListItem(
    val project: ProjectRecord,
    val environment: SandboxEnvironment?,
    val sharedWithCount: Int,
)

data class HomeUiState(
    val items: List<ProjectListItem> = emptyList(),
    val isLoading: Boolean = true,
)

class HomeViewModel(
    private val projectManager: ProjectManager,
    environmentManager: EnvironmentManager,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> =
        combine(projectManager.projects, environmentManager.environments) { projects, environments ->
            val usageCounts = projects.groupingBy { it.environmentId }.eachCount()
            HomeUiState(
                items = projects
                    .sortedByDescending { it.lastOpenedAtEpochMs }
                    .map { project ->
                        ProjectListItem(
                            project = project,
                            environment = environments.find { it.id == project.environmentId },
                            sharedWithCount = (usageCounts[project.environmentId] ?: 1) - 1,
                        )
                    },
                isLoading = false,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = HomeUiState(),
        )

    fun onProjectOpened(projectId: String) {
        viewModelScope.launch { projectManager.markOpened(projectId) }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
