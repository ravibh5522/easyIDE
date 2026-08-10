package dev.tabcode.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import dev.tabcode.app.data.UiPreferences
import dev.tabcode.app.ui.theme.ThemeMode
import dev.tabcode.sandbox.EnvironmentManager
import dev.tabcode.sandbox.ProjectManager
import dev.tabcode.sandbox.external.ExternalFolderSync
import dev.tabcode.sandbox.model.SandboxEnvironment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An environment plus how many projects depend on it - deletion needs that count. */
data class EnvironmentListItem(
    val environment: SandboxEnvironment,
    val usedByProjectCount: Int,
)

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM_DEFAULT,
    val environments: List<EnvironmentListItem> = emptyList(),
    /** Pre-selected when creating a project. */
    val defaultEnvironmentId: String? = null,
    /** Suggested storage folder for new projects; null means app storage. */
    val defaultProjectsFolderUri: String? = null,
    val defaultProjectsFolderName: String? = null,
)

class SettingsViewModel(
    private val uiPreferences: UiPreferences,
    private val environmentManager: EnvironmentManager,
    private val externalFolderSync: ExternalFolderSync,
    projectManager: ProjectManager,
) : ViewModel() {

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        uiPreferences.themeMode,
        environmentManager.environments,
        projectManager.projects,
        uiPreferences.defaultEnvironmentId,
        uiPreferences.defaultProjectsFolderUri,
    ) { theme, environments, projects, defaultEnvironmentId, folderUri ->
        val usage = projects.groupingBy { it.environmentId }.eachCount()
        SettingsUiState(
            themeMode = theme,
            environments = environments.map { environment ->
                EnvironmentListItem(environment, usage[environment.id] ?: 0)
            },
            // Ignore a stale default whose environment has since been deleted.
            defaultEnvironmentId = defaultEnvironmentId?.takeIf { id -> environments.any { it.id == id } },
            defaultProjectsFolderUri = folderUri,
            // A folder can vanish (SD card removed, permission revoked) between
            // sessions; a null name here just means "can't resolve it right now"
            // rather than a crash.
            defaultProjectsFolderName = folderUri?.let { uri ->
                runCatching { externalFolderSync.displayName(Uri.parse(uri)) }.getOrNull()
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = SettingsUiState(),
    )

    fun onThemeSelected(mode: ThemeMode) {
        viewModelScope.launch { uiPreferences.setThemeMode(mode) }
    }

    /** Tapping the current default clears it, so the choice is reversible. */
    fun onDefaultEnvironmentSelected(environmentId: String) {
        viewModelScope.launch {
            val next = if (uiState.value.defaultEnvironmentId == environmentId) null else environmentId
            uiPreferences.setDefaultEnvironmentId(next)
        }
    }

    /**
     * @param uri the tree the picker returned, or null to clear the default
     *   and go back to app storage. The caller must already have taken
     *   persistable access before calling this - this only saves the choice.
     */
    fun onDefaultProjectsFolderChosen(uri: String?) {
        viewModelScope.launch { uiPreferences.setDefaultProjectsFolderUri(uri) }
    }

    fun onDefaultProjectsFolderPickFailed() {
        _errorMessage.value = FOLDER_PICK_FAILED
    }

    /** Surfaces EnvironmentInUse as a message rather than failing silently. */
    fun onDeleteEnvironment(environmentId: String) {
        viewModelScope.launch {
            environmentManager.delete(environmentId).onFailure { cause ->
                _errorMessage.value = cause.message ?: DEFAULT_ERROR
            }
        }
    }

    fun onErrorShown() {
        _errorMessage.value = null
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        const val DEFAULT_ERROR = "Could not delete the environment"
        const val FOLDER_PICK_FAILED = "Could not get access to that folder"
    }
}
