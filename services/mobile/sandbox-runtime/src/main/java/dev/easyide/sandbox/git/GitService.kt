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
        amend: Boolean = false,
    ): GitResult<GitStatus> = run(projectDir) {
        it.commit(message, authorName, authorEmail, amend)
        it.status()
    }

    /** The saved commit-message draft; empty when there is none or the project is not a repository. */
    suspend fun readDraft(projectDir: File): String = run(projectDir) { it.readDraft() }.valueOrNull().orEmpty()

    suspend fun writeDraft(projectDir: File, text: String): GitResult<Unit> =
        run(projectDir) { it.writeDraft(text) }

    /** True when HEAD is already on some remote, so amending it would rewrite shared history. */
    suspend fun isHeadPushed(projectDir: File): GitResult<Boolean> = run(projectDir) { it.isHeadPushed() }

    suspend fun fileDiff(projectDir: File, path: String, source: DiffSource): GitResult<FileDiff> =
        run(projectDir) { it.fileDiff(path, source) }

    /** One comparison of a path between any two ends: two revisions, a revision and the index or working tree, or the index and the working tree. */
    suspend fun compare(projectDir: File, path: String, base: DiffEnd, head: DiffEnd): GitResult<FileDiff> =
        run(projectDir) { it.fileDiff(path, base, head) }

    /** The commit [rev] names with the files it changed; a failure when [rev] resolves to nothing. */
    suspend fun commitDetail(projectDir: File, rev: String): GitResult<GitCommitDetail> =
        run(projectDir) { it.commitDetail(rev) ?: throw IllegalArgumentException("Unknown revision: $rev") }

    suspend fun stageHunk(projectDir: File, path: String, hunk: DiffHunk): GitResult<GitStatus> =
        run(projectDir) { it.stageHunk(path, hunk); it.status() }

    suspend fun unstageHunk(projectDir: File, path: String, hunk: DiffHunk): GitResult<GitStatus> =
        run(projectDir) { it.unstageHunk(path, hunk); it.status() }

    suspend fun discardHunk(projectDir: File, path: String, hunk: DiffHunk): GitResult<GitStatus> =
        run(projectDir) { it.discardHunk(path, hunk); it.status() }

    suspend fun branches(projectDir: File): GitResult<List<GitBranch>> = run(projectDir) { it.branchInfos() }

    suspend fun createBranch(
        projectDir: File,
        name: String,
        startPoint: String?,
        checkout: Boolean,
    ): GitResult<GitStatus> = run(projectDir) { it.createBranch(name, startPoint, checkout); it.status() }

    suspend fun switchBranch(projectDir: File, name: String): GitResult<GitStatus> =
        run(projectDir) { it.switchBranch(name); it.status() }

    suspend fun deleteBranch(projectDir: File, name: String, force: Boolean): GitResult<GitStatus> =
        run(projectDir) { it.deleteBranch(name, force); it.status() }

    suspend fun renameBranch(projectDir: File, oldName: String, newName: String): GitResult<GitStatus> =
        run(projectDir) { it.renameBranch(oldName, newName); it.status() }

    suspend fun remotes(projectDir: File): GitResult<List<GitRemoteInfo>> = run(projectDir) { it.remoteInfos() }

    suspend fun addRemote(projectDir: File, name: String, url: String): GitResult<List<GitRemoteInfo>> =
        run(projectDir) { it.addRemote(name, url); it.remoteInfos() }

    suspend fun removeRemote(projectDir: File, name: String): GitResult<List<GitRemoteInfo>> =
        run(projectDir) { it.removeRemote(name); it.remoteInfos() }

    suspend fun stashes(projectDir: File): GitResult<List<GitStashEntry>> = run(projectDir) { it.stashEntries() }

    /** Success carries the new status; a nothing-to-stash outcome is a [GitResult.Failure], not a silent no-op. */
    suspend fun stash(projectDir: File, message: String?, includeUntracked: Boolean): GitResult<GitStatus> =
        run(projectDir) {
            check(it.stashPush(message, includeUntracked)) { "No local changes to stash" }
            it.status()
        }

    suspend fun stashPop(projectDir: File, index: Int): GitResult<GitStatus> =
        run(projectDir) { it.stashPop(index); it.status() }

    suspend fun stashApply(projectDir: File, index: Int): GitResult<GitStatus> =
        run(projectDir) { it.stashApply(index); it.status() }

    suspend fun stashDrop(projectDir: File, index: Int): GitResult<GitStatus> =
        run(projectDir) { it.stashDrop(index); it.status() }

    suspend fun log(projectDir: File, limit: Int = LOG_LIMIT): GitResult<List<GitCommit>> =
        run(projectDir) { it.logAllRefs(limit) }

    suspend fun refsByCommit(projectDir: File): GitResult<Map<String, List<GitRef>>> =
        run(projectDir) { it.refsByCommit() }

    /** A blank [message] makes a lightweight tag; otherwise an annotated one by the given tagger. */
    suspend fun createTag(
        projectDir: File,
        name: String,
        rev: String,
        message: String?,
        taggerName: String,
        taggerEmail: String,
    ): GitResult<Unit> = run(projectDir) { it.createTag(name, rev, message, taggerName, taggerEmail) }

    suspend fun checkoutDetached(projectDir: File, rev: String): GitResult<GitStatus> =
        run(projectDir) { it.checkoutDetached(rev); it.status() }

    /** A conflicting pick is a [GitResult.Failure] that leaves the conflict in the tree for the merge banner. */
    suspend fun cherryPick(projectDir: File, rev: String, authorName: String, authorEmail: String): GitResult<GitStatus> =
        run(projectDir) { it.cherryPick(rev, authorName, authorEmail); it.status() }

    /** Success carries null when the two revisions share no history. */
    suspend fun mergeBase(projectDir: File, a: String, b: String): GitResult<String?> =
        // run() reads a null block result as "not a repository", so the id travels in a list.
        when (val r = run(projectDir) { listOfNotNull(it.mergeBase(a, b)) }) {
            is GitResult.Success -> GitResult.Success(r.value.firstOrNull())
            is GitResult.Failure -> r
            GitResult.NotARepository -> GitResult.NotARepository
        }

    suspend fun changedBetween(projectDir: File, base: String, head: String): GitResult<List<GitCommitFile>> =
        run(projectDir) { it.changedBetween(base, head) }

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
            onFailure = { failureOf(it) },
        )
    }

    /** Separate from [run] so `init` is the one call allowed to create a repo. */
    suspend fun createRepository(projectDir: File): GitResult<GitStatus> =
        withContext(ioDispatcher) {
            runCatching {
                remember(projectDir, GitRepository.init(projectDir)).status()
            }.fold(
                onSuccess = { GitResult.Success(it) },
                onFailure = { failureOf(it) },
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
    data class Failure(
        val message: String,
        val kind: GitFailureKind = GitFailureKind.UNKNOWN,
    ) : GitResult<Nothing>
    data object NotARepository : GitResult<Nothing>

    fun valueOrNull(): T? = (this as? Success)?.value
}

/** Boundary translation of a JGit exception; the two typed cases are ones the UI offers a recovery for. */
internal fun failureOf(error: Throwable): GitResult.Failure = GitResult.Failure(
    message = error.message ?: error::class.java.simpleName,
    kind = when (error) {
        is org.eclipse.jgit.api.errors.CheckoutConflictException -> GitFailureKind.DIRTY_TREE
        is org.eclipse.jgit.api.errors.NotMergedException -> GitFailureKind.UNMERGED_BRANCH
        else -> GitFailureKind.UNKNOWN
    },
)
