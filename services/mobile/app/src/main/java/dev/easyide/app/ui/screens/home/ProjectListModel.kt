package dev.easyide.app.ui.screens.home

import dev.easyide.sandbox.git.GitSummary
import dev.easyide.sandbox.model.ProjectRecord
import dev.easyide.sandbox.model.SandboxEnvironment

/**
 * What Home knows about a project without opening a workspace: cheap to compute,
 * cached, and refreshed when Home comes back into view. Null on an item while
 * still loading, so a card can render its skeleton parts.
 *
 * @property git null when the project is not a git repository (or its repository could not be read).
 * @property externalFolderName display name of the linked SAF folder, when there is one.
 */
data class ProjectMeta(
    val git: GitSummary?,
    val language: ProjectLanguage?,
    val externalFolderName: String?,
    val loadedAtEpochMs: Long,
)

/**
 * Home shows projects together with the environment each one runs in, so a
 * shared environment is visible at a glance rather than buried in settings.
 */
data class ProjectListItem(
    val project: ProjectRecord,
    val environment: SandboxEnvironment?,
    val sharedWithCount: Int,
    val meta: ProjectMeta?,
)

enum class ProjectSort { RECENT, NAME }

/**
 * Applies the search box and the sort choice. Search is a case-insensitive
 * substring match over what a card shows (name, branch, linked folder), and
 * every whitespace-separated word must match somewhere, so "api main" finds
 * the `api-svc` project on `main`.
 */
fun List<ProjectListItem>.searchedAndSorted(query: String, sort: ProjectSort): List<ProjectListItem> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val matching = if (words.isEmpty()) this else filter { item ->
        val haystack = listOfNotNull(item.project.name, item.meta?.git?.branch, item.meta?.externalFolderName)
            .joinToString("\n")
            .lowercase()
        words.all { it in haystack }
    }
    return when (sort) {
        ProjectSort.RECENT -> matching.sortedWith(
            compareByDescending<ProjectListItem> { it.project.lastOpenedAtEpochMs }
                .thenBy { it.project.name.lowercase() }
                .thenBy { it.project.id },
        )
        ProjectSort.NAME -> matching.sortedWith(
            compareBy<ProjectListItem> { it.project.name.lowercase() }.thenBy { it.project.id },
        )
    }
}

/**
 * Keeps [selectedId] if it is still in [visible], otherwise falls back to the
 * first row - list-detail never shows an empty detail pane while rows exist,
 * and a deleted or filtered-out selection resolves without a special case.
 */
fun selectionIn(visible: List<ProjectListItem>, selectedId: String?): ProjectListItem? =
    visible.find { it.project.id == selectedId } ?: visible.firstOrNull()

/** How long a cached [ProjectMeta] is trusted before Home reloads it on return. */
object ProjectMetaPolicy {
    const val FRESH_FOR_MS = 30_000L

    /** On returning to Home, anything older than this is re-read: the workspace or terminal may have changed it. */
    const val RESUME_REFRESH_AFTER_MS = 2_000L

    /** Projects whose git status is read at once: JGit status walks the working tree, so a few at a time. */
    const val MAX_CONCURRENT_LOADS = 2

    /** Recently changed files shown in the detail pane. */
    const val RECENT_FILE_LIMIT = 5

    /**
     * Fresh while young, and only if read after the project was last opened: a
     * workspace session is when branch and changes move, so opening a project
     * invalidates what Home knew about it.
     */
    fun isFresh(meta: ProjectMeta?, project: ProjectRecord, nowMs: Long, maxAgeMs: Long = FRESH_FOR_MS): Boolean =
        meta != null &&
            meta.loadedAtEpochMs >= project.lastOpenedAtEpochMs &&
            nowMs - meta.loadedAtEpochMs < maxAgeMs
}

/** The project Home offers to continue: the one opened most recently, whatever the search box says. */
fun List<ProjectListItem>.resumeTarget(): ProjectListItem? = searchedAndSorted("", ProjectSort.RECENT).firstOrNull()

/** Branch as Home prints it: a trailing `*` when the working tree differs from HEAD, as prompts do. */
fun GitSummary.branchLabel(): String = if (isDirty) "$branch*" else branch

/** Whether the name typed to confirm a delete is the project's name; case and edge spaces do not count. */
fun deleteConfirmed(typed: String, projectName: String): Boolean =
    typed.trim().equals(projectName.trim(), ignoreCase = true)

enum class SizeUnit { MB, GB }

data class ProcessSize(val value: Double, val unit: SizeUnit)

private const val KB_PER_MB = 1024.0
private const val MB_PER_GB = 1024.0

/** A process's resident size in the unit that keeps the number short: whole MB up to a gigabyte, then GB. */
fun processSize(kb: Long): ProcessSize {
    val mb = kb / KB_PER_MB
    return if (mb >= MB_PER_GB) ProcessSize(mb / MB_PER_GB, SizeUnit.GB) else ProcessSize(mb, SizeUnit.MB)
}
