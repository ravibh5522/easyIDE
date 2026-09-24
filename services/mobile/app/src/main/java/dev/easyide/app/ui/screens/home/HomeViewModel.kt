package dev.easyide.app.ui.screens.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.easyide.sandbox.EnvironmentManager
import dev.easyide.sandbox.ProjectManager
import dev.easyide.sandbox.SandboxError
import dev.easyide.sandbox.external.ExternalFolderSync
import dev.easyide.sandbox.files.ProjectFiles
import dev.easyide.sandbox.files.RecentFiles
import dev.easyide.sandbox.git.GitCredentials
import dev.easyide.sandbox.git.GitResult
import dev.easyide.sandbox.git.GitService
import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.ProjectRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Home: the project list, its cheap per-project facts (git branch, language),
 * and the create / import / clone / rename / duplicate / delete operations.
 *
 * Per-project facts are read without opening a workspace, off the main thread,
 * a couple at a time, and cached with a freshness rule ([ProjectMetaPolicy]) so
 * scrolling and re-entering Home do not re-walk every working tree.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val projectManager: ProjectManager,
    private val environmentManager: EnvironmentManager,
    private val projectFiles: ProjectFiles,
    private val gitService: GitService,
    private val externalFolderSync: ExternalFolderSync,
    private val cloner: ProjectCloner,
    private val defaultEnvironmentId: Flow<String?>,
    private val running: RunningSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val ui = MutableStateFlow(LocalState())
    private val meta = MutableStateFlow<Map<String, ProjectMeta>>(emptyMap())
    private val refreshTick = MutableStateFlow(0)
    private val loadSlots = Semaphore(ProjectMetaPolicy.MAX_CONCURRENT_LOADS)

    private data class LocalState(
        val query: String = "",
        val sort: ProjectSort = ProjectSort.RECENT,
        val selectedId: String? = null,
        val detailOpen: Boolean = false,
        val dialog: HomeDialog? = null,
        val dialogFailure: HomeFailure? = null,
        val busy: HomeBusy? = null,
        val message: HomeMessage? = null,
    )

    /** A project and the time it was last opened: the scan of its files is redone when either changes. */
    private data class ScanKey(val projectId: String, val openedAt: Long)

    private fun ProjectListItem.scanKey() = ScanKey(project.id, project.lastOpenedAtEpochMs)

    private data class Core(val state: HomeUiState, val focusKey: ScanKey?, val resumeKey: ScanKey?)

    private val core: Flow<Core> = combine(
        projectManager.projects,
        environmentManager.environments,
        meta,
        ui,
        defaultEnvironmentId,
    ) { projects, environments, metas, local, defaultId ->
        val usage = projects.groupingBy { it.environmentId }.eachCount()
        val items = projects.map { project ->
            ProjectListItem(
                project = project,
                environment = environments.find { it.id == project.environmentId },
                sharedWithCount = (usage[project.environmentId] ?: 1) - 1,
                meta = metas[project.id],
            )
        }
        val visible = items.searchedAndSorted(local.query, local.sort)
        val selected = selectionIn(visible, local.selectedId)
        // A project page names its project even when the search box hides it from the list.
        val focus = items.find { it.project.id == local.selectedId } ?: selected
        val resume = items.resumeTarget()
        val ready = environments.filter { it.state == EnvironmentState.READY }
        Core(
            state = HomeUiState(
                isLoading = false,
                projectCount = projects.size,
                visible = visible,
                all = items,
                selected = selected,
                resume = resume,
                environments = environments,
                suggestedEnvironmentId = listOfNotNull(defaultId, ready.firstOrNull()?.id, environments.firstOrNull()?.id)
                    .firstOrNull { id -> environments.any { it.id == id } },
                query = local.query,
                sort = local.sort,
                detailOpen = local.detailOpen,
                dialog = local.dialog,
                dialogFailure = local.dialogFailure,
                busy = local.busy,
                message = local.message,
            ),
            focusKey = focus?.scanKey(),
            resumeKey = resume?.scanKey(),
        )
    }

    /** Re-read when the project or its last-opened time changes, or Home is resumed. */
    private fun recentFilesOf(key: Flow<ScanKey?>, limit: Int): Flow<RecentScan?> = combine(
        key.distinctUntilChanged(),
        refreshTick,
    ) { k, _ -> k?.projectId }.flatMapLatest { id ->
        flow {
            emit(id?.let { RecentScan(it, null) })
            if (id != null) emit(RecentScan(id, withContext(ioDispatcher) { RecentFiles.scan(projectFiles.projectRoot(id), limit) }))
        }
    }

    private val runningItems: Flow<List<RunningItem>> = combine(
        running.liveProjectIds,
        running.servers,
        projectManager.projects,
        environmentManager.environments,
        RunningAssembly::assemble,
    )

    val uiState: StateFlow<HomeUiState> = combine(
        core,
        recentFilesOf(core.map { it.focusKey }, ProjectMetaPolicy.RECENT_FILE_LIMIT),
        recentFilesOf(core.map { it.resumeKey }, RESUME_FILE_LIMIT),
        runningItems,
    ) { c, recent, resumeScan, live ->
        c.state.copy(recent = recent, resumeScan = resumeScan, running = live)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = HomeUiState(),
    )

    init {
        viewModelScope.launch {
            projectManager.projects.collect { projects -> loadStaleMeta(projects) }
        }
    }

    // ------------------------------------------------------------- list

    fun onQueryChanged(query: String) = ui.update { it.copy(query = query) }

    fun onSortChanged(sort: ProjectSort) = ui.update { it.copy(sort = sort) }

    fun onSelect(projectId: String) = ui.update { it.copy(selectedId = projectId, detailOpen = true) }

    fun onCloseDetail() = ui.update { it.copy(detailOpen = false) }

    /** Home came back into view: git state may have changed in the workspace or the terminal. */
    fun onResumed() {
        refreshTick.update { it + 1 }
        viewModelScope.launch {
            loadStaleMeta(projectManager.projects.first(), maxAgeMs = ProjectMetaPolicy.RESUME_REFRESH_AFTER_MS)
        }
    }

    fun onProjectOpened(projectId: String) {
        viewModelScope.launch { projectManager.markOpened(projectId) }
    }

    /** Stop on a Running row. Installs cannot be cancelled from here: their screen owns them. */
    fun onStop(id: RunningId) = when (id) {
        is RunningId.Session -> running.stopSession(id.projectId)
        is RunningId.Server -> running.stopServer(id.key)
        is RunningId.Install -> Unit
    }

    fun onMessageShown() = ui.update { it.copy(message = null) }

    // ---------------------------------------------------------- dialogs

    fun onDialog(dialog: HomeDialog?) = ui.update { it.copy(dialog = dialog, dialogFailure = null) }

    fun onFolderPicked(uri: Uri) = onDialog(
        HomeDialog.Import(uri.toString(), externalFolderSync.displayName(uri).orEmpty()),
    )

    fun onFolderPickFailed() = ui.update { it.copy(message = HomeMessage.FolderPickFailed) }

    // -------------------------------------------------------- operations

    fun rename(projectId: String, name: String) = operate(HomeBusy.RENAMING) {
        val renamed = projectManager.rename(projectId, name).getOrThrow()
        HomeMessage.Renamed(renamed.name)
    }

    fun duplicate(projectId: String, name: String) = operate(HomeBusy.DUPLICATING) {
        val copy = projectManager.duplicate(projectId, name).getOrThrow()
        ui.update { it.copy(selectedId = copy.id) }
        HomeMessage.Duplicated(copy.name)
    }

    fun delete(projectId: String) = operate(HomeBusy.DELETING) {
        val project = projectManager.projects.first().first { it.id == projectId }
        // The handle a previous visit to the workspace cached would otherwise pin the deleted directory's .git.
        gitService.release(rootOf(projectId))
        projectManager.delete(projectId, deleteFiles = true).getOrThrow()
        meta.update { it - projectId }
        ui.update { if (it.selectedId == projectId) it.copy(selectedId = null, detailOpen = false) else it }
        HomeMessage.Deleted(project.name)
    }

    fun changeEnvironment(projectId: String, environmentId: String) = operate(HomeBusy.CHANGING_ENVIRONMENT) {
        val updated = projectManager.reassignEnvironment(projectId, environmentId).getOrThrow()
        val label = environmentManager.environments.first().find { it.id == environmentId }?.label.orEmpty()
        HomeMessage.EnvironmentChanged(updated.name, label)
    }

    fun importFolder(treeUri: String, name: String, environmentId: String) = operate(HomeBusy.IMPORTING) {
        val project = projectManager.create(name, environmentId, externalFolderUri = treeUri).getOrThrow()
        ui.update { it.copy(selectedId = project.id) }
        HomeMessage.Imported(project.name)
    }

    /**
     * Creates an empty project, clones into it, and removes it again if the clone
     * fails - a failed clone must not leave an empty project behind.
     */
    fun clone(url: CloneUrl, name: String, environmentId: String) = operate(HomeBusy.CLONING) {
        val ready = environmentManager.environments.first().find { it.id == environmentId }?.state == EnvironmentState.READY
        if (!ready) throw HomeFailureException(HomeFailure.NoReadyEnvironment)

        val project = projectManager.create(name, environmentId, seedStarterFiles = false).getOrThrow()
        val target = rootOf(project.id)
        when (val result = cloner.clone(url.url, target, environmentId)) {
            is GitResult.Success -> Unit
            else -> {
                projectManager.delete(project.id, deleteFiles = true)
                throw HomeFailureException(cloneFailure(url.url, (result as? GitResult.Failure)?.message.orEmpty()))
            }
        }
        ui.update { it.copy(selectedId = project.id) }
        HomeMessage.Cloned(project.name)
    }

    private class HomeFailureException(val failure: HomeFailure) : Exception()

    private fun cloneFailure(url: String, output: String): HomeFailure = when (classifyCloneFailure(output)) {
        CloneFailure.AUTHENTICATION -> HomeFailure.CloneAuthentication(GitCredentials.hostOf(url) ?: url)
        CloneFailure.NOT_FOUND -> HomeFailure.CloneNotFound
        CloneFailure.OTHER -> HomeFailure.CloneFailed(lastOutputLine(output))
    }

    /**
     * Runs [block] with [busy] showing; on success closes the dialog and posts
     * the message it returns, on failure keeps the dialog open with the reason
     * so the user can correct the input and try again.
     */
    private fun operate(busy: HomeBusy, block: suspend () -> HomeMessage) {
        if (ui.value.busy != null) return
        ui.update { it.copy(busy = busy, dialogFailure = null) }
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { message -> ui.update { it.copy(busy = null, dialog = null, message = message) } }
                .onFailure { cause -> ui.update { it.copy(busy = null, dialogFailure = failureOf(cause)) } }
        }
    }

    private fun failureOf(cause: Throwable): HomeFailure = when (cause) {
        is HomeFailureException -> cause.failure
        is SandboxError.DuplicateName -> HomeFailure.NameTaken
        is IllegalArgumentException -> HomeFailure.NameBlank
        else -> HomeFailure.Other(cause.message)
    }

    // -------------------------------------------------------------- meta

    /** Main-thread only, like every caller: guards against loading one project twice at once. */
    private val loading = HashSet<String>()

    private fun loadStaleMeta(projects: List<ProjectRecord>, maxAgeMs: Long = ProjectMetaPolicy.FRESH_FOR_MS) {
        val now = clock()
        val known = meta.value
        // Projects that no longer exist must not keep their cache entry alive.
        meta.update { current -> current.filterKeys { id -> projects.any { it.id == id } } }
        projects.filter { it.id !in loading && !ProjectMetaPolicy.isFresh(known[it.id], it, now, maxAgeMs) }
            .forEach { project ->
                loading += project.id
                viewModelScope.launch {
                    try {
                        val loaded = loadSlots.withPermit { loadMeta(project) }
                        meta.update { it + (project.id to loaded) }
                    } finally {
                        loading -= project.id
                    }
                }
            }
    }

    private suspend fun rootOf(projectId: String) = withContext(ioDispatcher) { projectFiles.projectRoot(projectId) }

    private suspend fun loadMeta(project: ProjectRecord): ProjectMeta = withContext(ioDispatcher) {
        val git = gitService.summary(projectFiles.projectRoot(project.id)).valueOrNull()
        val rootNames = projectFiles.list(project.id).getOrNull().orEmpty().map { it.name }
        val sourceNames = projectFiles.list(project.id, SOURCE_DIR).getOrNull().orEmpty().map { it.name }
        ProjectMeta(
            git = git,
            language = ProjectLanguage.detect(rootNames, sourceNames),
            externalFolderName = project.externalFolderUri?.let { externalFolderSync.displayName(Uri.parse(it)) },
            loadedAtEpochMs = clock(),
        )
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        const val RESUME_FILE_LIMIT = 1
        const val SOURCE_DIR = "src"
    }
}
