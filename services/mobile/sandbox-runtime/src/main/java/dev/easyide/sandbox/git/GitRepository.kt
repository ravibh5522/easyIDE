package dev.easyide.sandbox.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.Closeable
import java.io.File

/**
 * A project's git repository, backed by JGit.
 *
 * JGit is used for everything that reads the object model - status, log, diff,
 * refs - because it runs in-process: no proot syscall tracing, no process spawn
 * per refresh, and typed objects instead of parsed porcelain. Network and
 * credential operations (clone, fetch, push) are a separate concern and belong
 * to the guest's own `git`, which already owns the user's config, hooks and the
 * credential-helper protocol.
 *
 * Every entry point is blocking and does real file I/O; callers run these off
 * the main thread.
 */
class GitRepository private constructor(
    private val git: Git,
    val workTree: File,
) : Closeable {

    private val repository: Repository get() = git.repository

    /** Branch name, or a short commit id when HEAD is detached. */
    fun currentBranch(): String {
        val full = repository.fullBranch ?: return DETACHED
        return if (full.startsWith(Constants.R_HEADS)) {
            full.removePrefix(Constants.R_HEADS)
        } else {
            repository.resolve(Constants.HEAD)?.name?.take(SHORT_ID_LENGTH) ?: DETACHED
        }
    }

    /** True before the first commit, when `HEAD` points at an unborn branch. */
    fun isUnborn(): Boolean = repository.resolve(Constants.HEAD) == null

    /** Just what a project list shows: the branch and how many paths differ from HEAD. */
    fun summary(): GitSummary = status().let { GitSummary(it.branch, it.totalChanges) }

    /**
     * Working tree and index state in one pass.
     *
     * JGit reports a file in several buckets at once (staged *and* modified
     * again, say), which is exactly what a source-control panel needs to show
     * two rows for one path.
     */
    fun status(): GitStatus {
        val s = git.status().call()
        val staged = buildList {
            s.added.forEach { add(GitChange(it, GitChangeType.ADDED, staged = true)) }
            s.changed.forEach { add(GitChange(it, GitChangeType.MODIFIED, staged = true)) }
            s.removed.forEach { add(GitChange(it, GitChangeType.DELETED, staged = true)) }
        }
        val unstaged = buildList {
            s.modified.forEach { add(GitChange(it, GitChangeType.MODIFIED, staged = false)) }
            s.missing.forEach { add(GitChange(it, GitChangeType.DELETED, staged = false)) }
            s.untracked.forEach { add(GitChange(it, GitChangeType.UNTRACKED, staged = false)) }
        }
        val conflicting = s.conflicting.map { GitChange(it, GitChangeType.CONFLICTED, staged = false) }
        return GitStatus(
            branch = currentBranch(),
            staged = staged.sortedBy { it.path },
            unstaged = unstaged.sortedBy { it.path },
            conflicting = conflicting.sortedBy { it.path },
            isClean = s.isClean,
        )
    }

    /** Stages adds, edits and deletes alike - `add` alone misses deletions. */
    fun stage(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val add = git.add()
        val remove = git.add().setUpdate(true)
        var hasExisting = false
        var hasMissing = false
        paths.forEach { path ->
            if (File(workTree, path).exists()) {
                add.addFilepattern(path); hasExisting = true
            } else {
                remove.addFilepattern(path); hasMissing = true
            }
        }
        if (hasExisting) add.call()
        if (hasMissing) remove.call()
    }

    fun unstage(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val reset = git.reset()
        paths.forEach { reset.addPath(it) }
        reset.call()
    }

    /** Discards working-tree edits. Untracked files are deleted outright. */
    fun discard(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val tracked = paths.filter { isTracked(it) }
        if (tracked.isNotEmpty()) {
            val checkout = git.checkout()
            tracked.forEach { checkout.addPath(it) }
            checkout.call()
        }
        (paths - tracked.toSet()).forEach { File(workTree, it).deleteRecursively() }
    }

    private fun isTracked(path: String): Boolean =
        repository.readDirCache().findEntry(path) >= 0

    fun commit(message: String, authorName: String, authorEmail: String): String =
        git.commit()
            .setMessage(message)
            .setAuthor(authorName, authorEmail)
            .setCommitter(authorName, authorEmail)
            .call()
            .name

    /**
     * History in topological order, with parents, ready for lane assignment by
     * a graph renderer. `RevSort.TOPO` keeps a branch's commits contiguous,
     * which is what stops the drawn lanes from zig-zagging.
     */
    fun log(limit: Int = DEFAULT_LOG_LIMIT): List<GitCommit> {
        if (isUnborn()) return emptyList()
        return git.log().setMaxCount(limit).call()
            .map { it.toGitCommit() }
    }

    /**
     * History across every ref, not just HEAD - what a graph view wants.
     *
     * Walked explicitly rather than through `LogCommand` because the sort has
     * to be set before the walk starts: `RevSort.TOPO` keeps each branch's
     * commits contiguous, which is what stops drawn lanes from zig-zagging.
     */
    fun logAllRefs(limit: Int = DEFAULT_LOG_LIMIT): List<GitCommit> {
        if (isUnborn()) return emptyList()
        return RevWalk(repository).use { walk ->
            walk.sort(RevSort.TOPO)
            walk.sort(RevSort.COMMIT_TIME_DESC, true)
            repository.refDatabase.getRefsByPrefix(Constants.R_REFS).forEach { ref ->
                val id: ObjectId = ref.peeledObjectId ?: ref.objectId ?: return@forEach
                runCatching { walk.markStart(walk.parseCommit(id)) }
            }
            walk.asSequence().take(limit).map { it.toGitCommit() }.toList()
        }
    }

    private fun RevCommit.toGitCommit() = GitCommit(
        id = name,
        shortId = name.take(SHORT_ID_LENGTH),
        parents = parents.map { it.name },
        subject = shortMessage,
        body = fullMessage,
        authorName = authorIdent.name,
        authorEmail = authorIdent.emailAddress,
        timestampMillis = authorIdent.whenAsInstant.toEpochMilli(),
    )

    fun branches(): List<String> = git.branchList().call()
        .mapNotNull { it.name?.removePrefix(Constants.R_HEADS) }
        .sorted()

    /** Remote URL for `origin`, or null when the repo has no remote yet. */
    fun originUrl(): String? =
        repository.config.getString("remote", "origin", "url")

    /** False once `.git` is deleted out from under an open handle. */
    fun gitDirExists(): Boolean = repository.directory.isDirectory

    // The repository, not the Git wrapper: a Git built around an existing
    // Repository (see [open]) does not close it, which leaked the handle.
    override fun close() = repository.close()

    companion object {
        const val DETACHED = "detached"
        private const val SHORT_ID_LENGTH = 7
        private const val DEFAULT_LOG_LIMIT = 200

        /** Walks up from [dir] the way git does, so a subdirectory still resolves. */
        fun open(dir: File): GitRepository? {
            val repo = FileRepositoryBuilder()
                .findGitDir(dir)
                .takeIf { it.gitDir != null }
                ?.readEnvironment()
                ?.build()
                ?: return null
            return GitRepository(Git(repo), repo.workTree ?: dir)
        }

        fun init(dir: File): GitRepository {
            val git = Git.init().setDirectory(dir).setInitialBranch(DEFAULT_BRANCH).call()
            return GitRepository(git, git.repository.workTree ?: dir)
        }

        fun isRepository(dir: File): Boolean = File(dir, Constants.DOT_GIT).exists()

        const val DEFAULT_BRANCH = "main"
    }
}

enum class GitChangeType { ADDED, MODIFIED, DELETED, UNTRACKED, CONFLICTED }

data class GitChange(
    val path: String,
    val type: GitChangeType,
    val staged: Boolean,
) {
    val name: String get() = path.substringAfterLast('/')
    val directory: String get() = path.substringBeforeLast('/', "")
}

data class GitStatus(
    val branch: String,
    val staged: List<GitChange>,
    val unstaged: List<GitChange>,
    val conflicting: List<GitChange>,
    val isClean: Boolean,
) {
    val totalChanges: Int get() = staged.size + unstaged.size + conflicting.size

    companion object {
        val NONE = GitStatus("", emptyList(), emptyList(), emptyList(), isClean = true)
    }
}

/** Branch plus number of changed paths (staged, unstaged, untracked, conflicting). */
data class GitSummary(val branch: String, val changedFiles: Int) {
    val isDirty: Boolean get() = changedFiles > 0
}

data class GitCommit(
    val id: String,
    val shortId: String,
    val parents: List<String>,
    val subject: String,
    val body: String,
    val authorName: String,
    val authorEmail: String,
    val timestampMillis: Long,
)
