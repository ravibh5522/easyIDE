package dev.easyide.app.ui.screens.newproject

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import dev.easyide.app.data.SandboxImages
import dev.easyide.sandbox.EnvironmentManager
import dev.easyide.sandbox.ProjectManager
import dev.easyide.sandbox.SandboxError
import dev.easyide.sandbox.external.ExternalFolderSync
import dev.easyide.sandbox.model.SandboxBackend
import dev.easyide.sandbox.model.SandboxEnvironment
import dev.easyide.sandbox.model.SandboxImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The two ways to give a project an environment, which is the core of the
 * sharing model in docs/decision/0005-sandbox-environment-sharing-model.md.
 */
enum class EnvironmentChoice {
    /** Provision a fresh rootfs for this project alone. */
    CREATE_NEW,

    /** Attach to an environment another project already set up. */
    REUSE_EXISTING,
}

/** Why creating a project failed, as data; the screen turns it into a sentence from string resources. */
sealed interface NewProjectError {
    data object NameTaken : NewProjectError
    data object NameBlank : NewProjectError
    data object FolderPickFailed : NewProjectError
    data class Other(val detail: String?) : NewProjectError
}

data class NewProjectUiState(
    val projectName: String = "",
    val choice: EnvironmentChoice = EnvironmentChoice.REUSE_EXISTING,
    val availableEnvironments: List<SandboxEnvironment> = emptyList(),
    val selectedEnvironmentId: String? = null,
    val newEnvironmentLabel: String = "",
    val availableBackends: List<SandboxBackend> = listOf(SandboxBackend.PROOT),
    val selectedBackend: SandboxBackend = SandboxBackend.PROOT,
    val availableImages: List<SandboxImage> = SandboxImages.CATALOG,
    val selectedImageId: String = SandboxImages.DEFAULT.id,
    /**
     * SAF tree URI files are mirrored to, or null for app storage only. See
     * [dev.easyide.sandbox.external.ExternalFolderSync] for why this is a
     * mirror, not the location the project actually runs from.
     */
    val externalFolderUri: String? = null,
    val externalFolderName: String? = null,
    val isSubmitting: Boolean = false,
    val error: NewProjectError? = null,
    val createdProjectId: String? = null,
) {
    /** Reuse is only offered once an environment exists to reuse. */
    val canReuse: Boolean get() = availableEnvironments.isNotEmpty()

    val canSubmit: Boolean
        get() = projectName.isNotBlank() && !isSubmitting && when (choice) {
            EnvironmentChoice.CREATE_NEW -> newEnvironmentLabel.isNotBlank()
            EnvironmentChoice.REUSE_EXISTING -> selectedEnvironmentId != null
        }
}

class NewProjectViewModel(
    private val projectManager: ProjectManager,
    private val environmentManager: EnvironmentManager,
    private val externalFolderSync: ExternalFolderSync,
    private val defaultEnvironmentId: kotlinx.coroutines.flow.Flow<String?>,
    private val defaultProjectsFolderUri: kotlinx.coroutines.flow.Flow<String?>,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewProjectUiState())
    val uiState: StateFlow<NewProjectUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(availableBackends = environmentManager.availableBackends()) }
        viewModelScope.launch {
            // Combined so the Settings default wins over "first in the list",
            // which is the point of having a default at all.
            kotlinx.coroutines.flow.combine(
                environmentManager.environments,
                defaultEnvironmentId,
            ) { environments, preferredId -> environments to preferredId }
                .collect { (environments, preferredId) ->
                    val preferred = preferredId?.takeIf { id -> environments.any { it.id == id } }
                    _uiState.update { state ->
                        state.copy(
                            availableEnvironments = environments,
                            // Default to reusing when possible; creating a second
                            // full rootfs is the expensive, deliberate choice.
                            choice = if (environments.isEmpty()) EnvironmentChoice.CREATE_NEW else state.choice,
                            selectedEnvironmentId = state.selectedEnvironmentId
                                ?: preferred
                                ?: environments.firstOrNull()?.id,
                        )
                    }
                }
        }
        // Pre-fill the Settings default so picking a storage folder is a
        // one-tap confirmation rather than a repeat of the same navigation
        // every single time, while still leaving it fully overridable below.
        viewModelScope.launch {
            val defaultUri = defaultProjectsFolderUri.first() ?: return@launch
            if (!externalFolderSync.hasAccess(Uri.parse(defaultUri))) return@launch
            _uiState.update {
                it.copy(
                    externalFolderUri = defaultUri,
                    externalFolderName = externalFolderSync.displayName(Uri.parse(defaultUri)),
                )
            }
        }
    }

    fun onProjectNameChanged(value: String) = _uiState.update { it.copy(projectName = value, error = null) }

    fun onChoiceChanged(choice: EnvironmentChoice) = _uiState.update { it.copy(choice = choice, error = null) }

    fun onEnvironmentSelected(id: String) = _uiState.update { it.copy(selectedEnvironmentId = id) }

    fun onNewEnvironmentLabelChanged(value: String) = _uiState.update { it.copy(newEnvironmentLabel = value) }

    fun onBackendSelected(backend: SandboxBackend) = _uiState.update { it.copy(selectedBackend = backend) }

    /** @param uri already granted persistable access by the picker's caller. */
    fun onExternalFolderChosen(uri: Uri) {
        _uiState.update {
            it.copy(externalFolderUri = uri.toString(), externalFolderName = externalFolderSync.displayName(uri))
        }
    }

    fun onExternalFolderCleared() = _uiState.update { it.copy(externalFolderUri = null, externalFolderName = null) }

    fun onExternalFolderPickFailed() = _uiState.update { it.copy(error = NewProjectError.FolderPickFailed) }

    fun onImageSelected(imageId: String) = _uiState.update { it.copy(selectedImageId = imageId) }

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return

        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            resolveEnvironmentId(state)
                .mapCatching { environmentId ->
                    projectManager.create(
                        name = state.projectName,
                        environmentId = environmentId,
                        externalFolderUri = state.externalFolderUri,
                    ).getOrThrow()
                }
                .onSuccess { project ->
                    _uiState.update { it.copy(isSubmitting = false, createdProjectId = project.id) }
                }
                .onFailure { cause ->
                    _uiState.update { it.copy(isSubmitting = false, error = errorOf(cause)) }
                }
        }
    }

    private suspend fun resolveEnvironmentId(state: NewProjectUiState): Result<String> =
        when (state.choice) {
            EnvironmentChoice.REUSE_EXISTING ->
                state.selectedEnvironmentId?.let { Result.success(it) }
                    ?: Result.failure(IllegalStateException("No environment selected"))

            EnvironmentChoice.CREATE_NEW ->
                environmentManager.create(
                    label = state.newEnvironmentLabel,
                    backend = state.selectedBackend,
                    imageId = state.selectedImageId,
                ).map { it.id }
        }

    private fun errorOf(cause: Throwable): NewProjectError = when (cause) {
        is SandboxError.DuplicateName -> NewProjectError.NameTaken
        is IllegalArgumentException -> NewProjectError.NameBlank
        else -> NewProjectError.Other(cause.message)
    }
}
