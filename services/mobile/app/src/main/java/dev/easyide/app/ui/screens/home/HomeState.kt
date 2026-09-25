package dev.easyide.app.ui.screens.home

import dev.easyide.sandbox.files.RecentFile
import dev.easyide.sandbox.git.GitResult
import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.SandboxEnvironment
import java.io.File

/** Runs `git clone` for a new project; the real one is the sandbox's own `git` (see GitRemote). */
fun interface ProjectCloner {
    suspend fun clone(url: String, targetDir: File, environmentId: String): GitResult<String>
}

/** Files read for one project; [files] is null while the scan runs, so a page can tell "loading" from "none". */
data class RecentScan(val projectId: String, val files: List<RecentFile>?)

/** A modal the user is in the middle of. Held by the ViewModel so it survives rotation. */
sealed interface HomeDialog {
    data class Rename(val projectId: String) : HomeDialog
    data class Duplicate(val projectId: String) : HomeDialog
    data class Delete(val projectId: String) : HomeDialog
    data class ChangeEnvironment(val projectId: String) : HomeDialog
    data object Clone : HomeDialog

    /** A folder the user picked (already granted persistable access), about to become a project. */
    data class Import(val treeUri: String, val suggestedName: String) : HomeDialog
}

/** A long operation in progress, for a spinner in the open dialog. */
enum class HomeBusy { RENAMING, DUPLICATING, DELETING, CHANGING_ENVIRONMENT, IMPORTING, CLONING }

/** Why an operation failed, in terms the UI turns into a sentence. Never carries UI text itself. */
sealed interface HomeFailure {
    data object NameTaken : HomeFailure
    data object NameBlank : HomeFailure
    data object NoReadyEnvironment : HomeFailure
    data class CloneAuthentication(val host: String) : HomeFailure
    data object CloneNotFound : HomeFailure
    data class CloneFailed(val detail: String) : HomeFailure
    data class Other(val detail: String?) : HomeFailure
}

/** What just happened, shown once as a snackbar. */
sealed interface HomeMessage {
    data class Renamed(val name: String) : HomeMessage
    data class Duplicated(val name: String) : HomeMessage
    data class Deleted(val name: String) : HomeMessage
    data class EnvironmentChanged(val name: String, val environment: String) : HomeMessage
    data class Imported(val name: String) : HomeMessage
    data class Cloned(val name: String) : HomeMessage
    data object FolderPickFailed : HomeMessage
}

data class HomeUiState(
    val isLoading: Boolean = true,
    /** All projects, before search; distinguishes "no projects" from "nothing matches". */
    val projectCount: Int = 0,
    val visible: List<ProjectListItem> = emptyList(),
    /** Every project, unfiltered and unsorted: dialogs address a project regardless of the search box. */
    val all: List<ProjectListItem> = emptyList(),
    val selected: ProjectListItem? = null,
    /** The project Home offers to continue, and its newest file. */
    val resume: ProjectListItem? = null,
    val resumeScan: RecentScan? = null,
    /** Recently changed files of the focused project (the selected one, or the one whose page is open). */
    val recent: RecentScan? = null,
    val running: List<RunningItem> = emptyList(),
    val environments: List<SandboxEnvironment> = emptyList(),
    /** The environment new imports and clones should default to. */
    val suggestedEnvironmentId: String? = null,
    val query: String = "",
    val sort: ProjectSort = ProjectSort.RECENT,
    val dialog: HomeDialog? = null,
    val dialogFailure: HomeFailure? = null,
    val busy: HomeBusy? = null,
    val message: HomeMessage? = null,
) {
    /** No environment exists yet, so nothing can run: Home offers to install Linux. */
    val needsLinux: Boolean get() = !isLoading && environments.isEmpty()

    /** Every project's name by id, for validating a rename or duplicate against the others. */
    val projectNames: Map<String, String> get() = all.associate { it.project.id to it.project.name }

    fun find(projectId: String): ProjectListItem? = all.find { it.project.id == projectId }

    /** Null while the scan of [projectId] is unfinished or belongs to another project. */
    fun recentFilesOf(projectId: String): List<RecentFile>? = recent?.takeIf { it.projectId == projectId }?.files

    val resumeFile: RecentFile? get() = resumeScan?.takeIf { it.projectId == resume?.project?.id }?.files?.firstOrNull()

    /** Projects using each environment, for the Environment section. */
    fun projectsIn(environmentId: String): Int = all.count { it.project.environmentId == environmentId }

    val readyEnvironments: List<SandboxEnvironment>
        get() = environments.filter { it.state == EnvironmentState.READY }
}
