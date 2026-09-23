package dev.easyide.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import dev.easyide.app.data.UiPreferences
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.SettingsStore
import dev.easyide.sandbox.EnvironmentManager
import dev.easyide.sandbox.ProjectManager
import dev.easyide.sandbox.external.ExternalFolderSync
import dev.easyide.sandbox.model.SandboxEnvironment
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
    val settings: SettingsSnapshot = SettingsSnapshot.DEFAULTS,
    val environments: List<EnvironmentListItem> = emptyList(),
    /** Pre-selected when creating a project. */
    val defaultEnvironmentId: String? = null,
    /** Suggested storage folder for new projects; null means app storage. */
    val defaultProjectsFolderUri: String? = null,
    val defaultProjectsFolderName: String? = null,
)

class SettingsViewModel(
    private val uiPreferences: UiPreferences,
    private val settingsStore: SettingsStore,
    private val environmentManager: EnvironmentManager,
    private val externalFolderSync: ExternalFolderSync,
    projectManager: ProjectManager,
) : ViewModel(), SettingActions {

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsStore.snapshot,
        environmentManager.environments,
        projectManager.projects,
        uiPreferences.defaultEnvironmentId,
        uiPreferences.defaultProjectsFolderUri,
    ) { settings, environments, projects, defaultEnvironmentId, folderUri ->
        val usage = projects.groupingBy { it.environmentId }.eachCount()
        SettingsUiState(
            settings = settings,
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

    override fun <T> set(setting: Setting<T>, value: T) {
        viewModelScope.launch { settingsStore.set(setting, value) }
    }

    override fun reset(setting: Setting<*>) {
        viewModelScope.launch { settingsStore.reset(setting) }
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
