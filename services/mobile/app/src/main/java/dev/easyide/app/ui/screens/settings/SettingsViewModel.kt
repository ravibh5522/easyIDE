package dev.easyide.app.ui.screens.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.easyide.app.data.UiPreferences
import dev.easyide.app.extensions.ContributedThemeCatalog
import dev.easyide.app.extensions.adapters.ContributedThemes
import dev.easyide.app.data.settings.ConfigTarget
import dev.easyide.app.data.settings.ImportMode
import dev.easyide.app.data.settings.ImportPreview
import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.ProfileManager
import dev.easyide.app.data.settings.ProjectTrust
import dev.easyide.app.data.settings.SafeModeReason
import dev.easyide.app.data.settings.SafeModeState
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.data.settings.SettingEdit
import dev.easyide.app.data.settings.SettingsQuery
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.SettingsStore
import dev.easyide.app.data.settings.SettingsTransfer
import dev.easyide.app.data.settings.TrustRequest
import dev.easyide.app.ui.theme.ThemeMode
import dev.easyide.app.ui.theme.toEditorColors
import dev.easyide.sandbox.EnvironmentManager
import dev.easyide.sandbox.ProjectManager
import dev.easyide.sandbox.external.ExternalFolderSync
import dev.easyide.sandbox.model.SandboxEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** An environment plus how many projects depend on it - deletion needs that count. */
data class EnvironmentListItem(
    val environment: SandboxEnvironment,
    val usedByProjectCount: Int,
)

/** A project that can be picked as the Project layer target. */
data class ProjectChoice(val id: String, val name: String, val environmentId: String)

/** Which layer the Settings screen shows and writes (LLD 17 "layer tabs"). */
sealed interface LayerTab {
    val layer: LayerId
    val target: ConfigTarget
    val query: SettingsQuery

    data object User : LayerTab {
        override val layer = LayerId.USER
        override val target = ConfigTarget.USER
        override val query = SettingsQuery.GLOBAL
    }

    data class Environment(val envId: String) : LayerTab {
        override val layer = LayerId.ENVIRONMENT
        override val target get() = ConfigTarget(LayerId.ENVIRONMENT, envId = envId)
        override val query get() = SettingsQuery(envId = envId)
    }

    data class Project(val projectId: String, val envId: String) : LayerTab {
        override val layer = LayerId.PROJECT
        override val target get() = ConfigTarget(LayerId.PROJECT, envId = envId, projectId = projectId)
        override val query get() = SettingsQuery(envId = envId, projectId = projectId)
    }
}

data class SettingsUiState(
    val settings: SettingsSnapshot = SettingsSnapshot.DEFAULTS,
    val tab: LayerTab = LayerTab.User,
    val environments: List<EnvironmentListItem> = emptyList(),
    val projects: List<ProjectChoice> = emptyList(),
    /** Pre-selected when creating a project. */
    val defaultEnvironmentId: String? = null,
    /** Suggested storage folder for new projects; null means app storage. */
    val defaultProjectsFolderName: String? = null,
)

/** Profiles, safe mode and the selected project's trust question: device-level state. */
data class SettingsSystemState(
    val profiles: List<String> = emptyList(),
    val activeProfile: String = "",
    val safeMode: SafeModeReason? = null,
    val trust: TrustRequest? = null,
)

/** A one-shot outcome shown as a snackbar. */
enum class SettingsMessage {
    WRITE_FAILED, EXPORTED, EXPORT_FAILED, IMPORTED, IMPORT_FAILED, PROFILE_FAILED,
    FOLDER_PICK_FAILED, ENVIRONMENT_DELETE_FAILED, SAFE_MODE_EXIT_FAILED,
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val uiPreferences: UiPreferences,
    private val settingsStore: SettingsStore,
    private val profileManager: ProfileManager,
    private val safeModeState: SafeModeState,
    private val projectTrust: ProjectTrust,
    private val transfer: SettingsTransfer,
    private val environmentManager: EnvironmentManager,
    private val externalFolderSync: ExternalFolderSync,
    projectManager: ProjectManager,
    themes: ContributedThemeCatalog,
) : ViewModel(), SettingActions, ThemeActions {

    private val tab = MutableStateFlow<LayerTab>(LayerTab.User)

    private val _message = MutableStateFlow<SettingsMessage?>(null)
    val message: StateFlow<SettingsMessage?> = _message.asStateFlow()

    /** Error text from the environment manager, which already words its refusals. */
    private val _errorDetail = MutableStateFlow<String?>(null)
    val errorDetail: StateFlow<String?> = _errorDetail.asStateFlow()

    private val _importPreview = MutableStateFlow<ImportPreview?>(null)
    val importPreview: StateFlow<ImportPreview?> = _importPreview.asStateFlow()

    val jsonEditor = SettingsJsonEditorController(viewModelScope, settingsStore, profileManager)

    val uiState: StateFlow<SettingsUiState> = combine(
        tab.flatMapLatest { t -> settingsStore.snapshot(t.query) },
        tab,
        combine(environmentManager.environments, projectManager.projects) { e, p -> e to p },
        uiPreferences.defaultEnvironmentId,
        uiPreferences.defaultProjectsFolderUri,
    ) { settings, t, (environments, projects), defaultEnvironmentId, folderUri ->
        val usage = projects.groupingBy { it.environmentId }.eachCount()
        SettingsUiState(
            settings = settings,
            tab = t,
            environments = environments.map { environment -> EnvironmentListItem(environment, usage[environment.id] ?: 0) },
            projects = projects.map { ProjectChoice(it.id, it.name, it.environmentId) },
            // Ignore a stale default whose environment has since been deleted.
            defaultEnvironmentId = defaultEnvironmentId?.takeIf { id -> environments.any { it.id == id } },
            // A folder can vanish (SD card removed, permission revoked) between
            // sessions; a null name here just means "can't resolve it right now".
            defaultProjectsFolderName = folderUri?.let { uri ->
                runCatching { externalFolderSync.displayName(Uri.parse(uri)) }.getOrNull()
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), SettingsUiState())

    val systemState: StateFlow<SettingsSystemState> = combine(
        profileManager.profiles,
        profileManager.active,
        safeModeState.active,
        tab.flatMapLatest { t -> if (t is LayerTab.Project) settingsStore.trustRequest(t.projectId) else flowOf(null) },
    ) { profiles, active, safe, trust -> SettingsSystemState(profiles, active, safe, trust) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), SettingsSystemState())

    /** Preview cards for the contributed themes that load; a broken one is logged and left out. */
    val themeCards: StateFlow<List<ContributedThemeCard>> = themes.themes
        .mapLatest { list ->
            list.mapNotNull { t ->
                themes.tokensFor(t)?.let { ContributedThemeCard(t.value.label, ContributedThemes.refOf(t), it.toEditorColors()) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    fun onTabSelected(next: LayerTab) {
        tab.value = next
    }

    override fun <T> set(setting: Setting<T>, value: T, language: String?) {
        write(listOf(SettingEdit(setting.key, language, setting.encode(value))))
    }

    override fun setJson(setting: Setting<*>, value: JsonElement, language: String?) {
        write(listOf(SettingEdit(setting.key, language, value)))
    }

    override fun reset(setting: Setting<*>, language: String?) {
        write(listOf(SettingEdit(setting.key, language, null)))
    }

    /** A built-in card also clears an extension theme, which would otherwise keep winning. */
    override fun selectBuiltInTheme(mode: ThemeMode) = write(listOf(
        SettingEdit(SettingsSchema.themeMode.key, null, SettingsSchema.themeMode.encode(mode)),
        SettingEdit(SettingsSchema.colorTheme.key, null, null),
    ))

    override fun selectContributedTheme(label: String) = set(SettingsSchema.colorTheme, label, null)

    override fun resetTheme() = write(listOf(
        SettingEdit(SettingsSchema.themeMode.key, null, null),
        SettingEdit(SettingsSchema.colorTheme.key, null, null),
    ))

    fun resetAll() {
        viewModelScope.launch {
            settingsStore.resetAll(tab.value.target).onFailure { _message.value = SettingsMessage.WRITE_FAILED }
        }
    }

    fun openJson() = jsonEditor.openLayer(tab.value.target)

    fun openKeybindingsJson() = jsonEditor.openKeybindings()

    fun onExport(target: Uri) {
        viewModelScope.launch {
            _message.value = if (transfer.export(target).isSuccess) SettingsMessage.EXPORTED else SettingsMessage.EXPORT_FAILED
        }
    }

    fun onImportPicked(source: Uri) {
        viewModelScope.launch {
            transfer.preview(source)
                .onSuccess { _importPreview.value = it }
                .onFailure { _message.value = SettingsMessage.IMPORT_FAILED }
        }
    }

    fun onImportConfirmed(mode: ImportMode) {
        val preview = _importPreview.value ?: return
        _importPreview.value = null
        viewModelScope.launch {
            _message.value = if (transfer.apply(preview.bundle, mode).isSuccess) SettingsMessage.IMPORTED else SettingsMessage.IMPORT_FAILED
        }
    }

    fun onImportCancelled() {
        _importPreview.value = null
    }

    fun switchProfile(name: String) = profileAction { profileManager.switchTo(name) }

    fun createProfile(name: String, copyFrom: String?) = profileAction { profileManager.create(name.trim(), copyFrom) }

    fun renameProfile(from: String, to: String) = profileAction { profileManager.rename(from, to.trim()) }

    fun deleteProfile(name: String) = profileAction { profileManager.delete(name) }

    /** [onExited] recreates the activity, so everything restarts without safe mode. */
    fun exitSafeMode(onExited: () -> Unit) {
        viewModelScope.launch {
            if (safeModeState.exit().isSuccess) onExited() else _message.value = SettingsMessage.SAFE_MODE_EXIT_FAILED
        }
    }

    fun allowProjectExec(request: TrustRequest) {
        viewModelScope.launch { projectTrust.allow(request.projectId, request.fingerprint) }
    }

    fun denyProjectExec(request: TrustRequest) {
        viewModelScope.launch { projectTrust.deny(request.projectId, request.fingerprint) }
    }

    fun deferProjectExec(request: TrustRequest) = projectTrust.defer(request.projectId, request.fingerprint)

    /** Forgets the stored decision so the question is asked again. */
    fun reviewProjectTrust(projectId: String) {
        viewModelScope.launch { projectTrust.reset(projectId) }
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
        _message.value = SettingsMessage.FOLDER_PICK_FAILED
    }

    /** Surfaces EnvironmentInUse as a message rather than failing silently. */
    fun onDeleteEnvironment(environmentId: String) {
        viewModelScope.launch {
            environmentManager.delete(environmentId).onFailure { cause ->
                val detail = cause.message
                if (detail != null) _errorDetail.value = detail else _message.value = SettingsMessage.ENVIRONMENT_DELETE_FAILED
            }
        }
    }

    fun onMessageShown() {
        _message.value = null
        _errorDetail.value = null
    }

    private fun write(edits: List<SettingEdit>) {
        viewModelScope.launch {
            settingsStore.write(tab.value.target, edits).onFailure { _message.value = SettingsMessage.WRITE_FAILED }
        }
    }

    private fun profileAction(block: suspend () -> Result<Unit>) {
        viewModelScope.launch { block().onFailure { _message.value = SettingsMessage.PROFILE_FAILED } }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
