package dev.easyide.sandbox.git

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Coroutine-facing entry point for a project's git repository.
 *
 * The repository handle is opened per call rather than held: JGit keeps file
 * descriptors and a cached index, and a long-lived handle would go stale
 * whenever the guest's own `git` (or Claude Code, or the terminal) writes to
 * `.git` behind our back - which in this app is routine, not exceptional.
 * Opening is cheap next to the I/O each operation already does.
 *
 * Every call is a real filesystem boundary, so failures are surfaced as
 * [GitResult] rather than thrown: a corrupt or half-cloned repository must
 * degrade to "no source control" instead of taking the workspace down.
 */
class GitService(private val ioDispatcher: CoroutineDispatcher) {

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
     * Opens the repository, creating one when [create] is set, and runs [block]
     * against it. Anything thrown becomes [GitResult.Failure].
     */
    private suspend fun <T> run(
        projectDir: File,
        create: Boolean = false,
        block: (GitRepository) -> T,
    ): GitResult<T> = withContext(ioDispatcher) {
        runCatching {
            val repo = GitRepository.open(projectDir)
                ?: if (create) GitRepository.init(projectDir) else return@runCatching null
            repo.use(block)
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
                GitRepository.init(projectDir).use { it.status() }
            }.fold(
                onSuccess = { GitResult.Success(it) },
                onFailure = { GitResult.Failure(it.message ?: it::class.java.simpleName) },
            )
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
