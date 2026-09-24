package dev.easyide.sandbox.git

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Coroutine-facing entry point for a project's git repository.
 *
 * One JGit handle is kept per project until [release]: the workspace refreshes
 * status on every filesystem event, and re-opening per call re-read config,
 * refs and pack indexes each time. Keeping it is safe against the guest's own
 * `git` (or Claude Code, or the terminal) writing to `.git` behind our back -
 * JGit re-checks the index, refs and config against their on-disk snapshots
 * on every read. A handle whose `.git` was deleted is dropped and re-opened.
 *
 * Every call is a real filesystem boundary, so failures are surfaced as
 * [GitResult] rather than thrown: a corrupt or half-cloned repository must
 * degrade to "no source control" instead of taking the workspace down.
 */
class GitService(private val ioDispatcher: CoroutineDispatcher) {

    private val openRepositories = mutableMapOf<String, GitRepository>()

    suspend fun isRepository(projectDir: File): Boolean = withContext(ioDispatcher) {
        GitRepository.isRepository(projectDir)
    }

    /** Creates a repository if the project does not have one yet. */
    suspend fun initRepository(projectDir: File): GitResult<Unit> = run(projectDir) { it ->
        // `init` on an existing repository is a no-op in git, but being explicit
        // keeps the caller's intent readable at the call site.
        Unit.also { _ -> it.currentBranch() }
    }

    suspend fun status(projectDir: File): GitResult<GitStatus> = run(projectDir) { it.status() }

    /**
     * Branch and change count without opening a workspace, for project lists.
     * Reuses a handle the workspace already holds, but never caches a new one:
     * a list of many projects would otherwise pin a JGit repository per row.
     */
    suspend fun summary(projectDir: File): GitResult<GitSummary> = withContext(ioDispatcher) {
        runCatching {
            if (!GitRepository.isRepository(projectDir)) return@runCatching null
            val cached = synchronized(openRepositories) {
                openRepositories[projectDir.absolutePath]?.takeIf { it.gitDirExists() }
            }
            cached?.summary() ?: GitRepository.open(projectDir)?.use { it.summary() }
        }.fold(
            onSuccess = { value -> if (value == null) GitResult.NotARepository else GitResult.Success(value) },
            onFailure = { GitResult.Failure(it.message ?: it::class.java.simpleName) },
        )
    }

    suspend fun stage(projectDir: File, paths: Collection<String>): GitResult<GitStatus> =
        run(projectDir) { it.stage(paths); it.status() }

    suspend fun unstage(projectDir: File, paths: Collection<String>): GitResult<GitStatus> =
        run(projectDir) { it.unstage(paths); it.status() }

    suspend fun discard(projectDir: File, paths: Collection<String>): GitResult<GitStatus> =
        run(projectDir) { it.discard(paths); it.status() }

    suspend fun commit(
        projectDir: File,
        message: String,
        authorName: String,
        authorEmail: String,
    ): GitResult<GitStatus> = run(projectDir) {
        it.commit(message, authorName, authorEmail)
        it.status()
    }

    suspend fun log(projectDir: File, limit: Int = LOG_LIMIT): GitResult<List<GitCommit>> =
        run(projectDir) { it.logAllRefs(limit) }

    /**
     * Runs [block] against the project's cached repository, creating one when
     * [create] is set. Anything thrown becomes [GitResult.Failure].
     */
    private suspend fun <T> run(
        projectDir: File,
        create: Boolean = false,
        block: (GitRepository) -> T,
    ): GitResult<T> = withContext(ioDispatcher) {
        runCatching {
            val repo = repositoryFor(projectDir)
                ?: if (create) remember(projectDir, GitRepository.init(projectDir)) else return@runCatching null
            block(repo)
        }.fold(
            onSuccess = { value ->
                if (value == null) GitResult.NotARepository else GitResult.Success(value)
            },
            onFailure = { GitResult.Failure(it.message ?: it::class.java.simpleName) },
        )
    }

    /** Separate from [run] so `init` is the one call allowed to create a repo. */
    suspend fun createRepository(projectDir: File): GitResult<GitStatus> =
        withContext(ioDispatcher) {
            runCatching {
                remember(projectDir, GitRepository.init(projectDir)).status()
            }.fold(
                onSuccess = { GitResult.Success(it) },
                onFailure = { GitResult.Failure(it.message ?: it::class.java.simpleName) },
            )
        }

    /** Closes [projectDir]'s cached handle; call when its workspace goes away. */
    fun release(projectDir: File) {
        synchronized(openRepositories) { openRepositories.remove(projectDir.absolutePath) }?.close()
    }

    private fun repositoryFor(projectDir: File): GitRepository? = synchronized(openRepositories) {
        val key = projectDir.absolutePath
        val cached = openRepositories[key]
        if (cached != null && cached.gitDirExists()) return cached
        openRepositories.remove(key)?.close()
        GitRepository.open(projectDir)?.also { openRepositories[key] = it }
    }

    private fun remember(projectDir: File, repo: GitRepository): GitRepository = synchronized(openRepositories) {
        openRepositories.put(projectDir.absolutePath, repo)?.close()
        repo
    }

    private companion object {
        const val LOG_LIMIT = 200
    }
}

/**
 * Three outcomes, not two: "this project simply is not a repository" is an
 * ordinary state the UI renders differently, not an error to report.
 */
sealed interface GitResult<out T> {
    data class Success<T>(val value: T) : GitResult<T>
    data class Failure(val message: String) : GitResult<Nothing>
    data object NotARepository : GitResult<Nothing>

    fun valueOrNull(): T? = (this as? Success)?.value
}
