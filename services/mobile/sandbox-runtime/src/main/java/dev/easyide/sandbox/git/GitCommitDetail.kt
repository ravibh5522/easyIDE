package dev.easyide.sandbox.git

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.io.DisabledOutputStream

/** How a commit changed one file; renames are not detected, so one is a delete plus an add. */
data class GitCommitFile(val path: String, val type: GitChangeType, val added: Int, val removed: Int)

/**
 * One commit as the commit document shows it: the commit itself and what it changed relative to its
 * first parent (relative to nothing for a root commit). [base] is the revision to diff each file
 * against, so a file row can name its comparison without knowing about parents.
 */
data class GitCommitDetail(val commit: GitCommit, val base: DiffEnd, val files: List<GitCommitFile>)

/** Null when [rev] names nothing in this repository. */
fun GitRepository.commitDetail(rev: String): GitCommitDetail? {
    val id = repository.resolve("$rev^{commit}") ?: return null
    return RevWalk(repository).use { walk ->
        val commit = walk.parseCommit(id)
        val parent = commit.parents.firstOrNull()?.name
        val base = if (parent == null) DiffEnd.Empty else DiffEnd.Rev(parent)
        val files = repository.newObjectReader().use { reader ->
            DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
                formatter.setRepository(repository)
                formatter.isDetectRenames = false
                formatter.setBinaryFileThreshold(GitDiffLimits.MAX_TEXT_BYTES.toInt())
                formatter.scan(iteratorFor(base, reader), iteratorFor(DiffEnd.Rev(commit.name), reader))
                    .map { entry -> commitFile(formatter, entry) }
            }
        }
        GitCommitDetail(commit.toGitCommit(), base, files.sortedBy { it.path })
    }
}

private fun commitFile(formatter: DiffFormatter, entry: DiffEntry): GitCommitFile {
    val edits = formatter.toFileHeader(entry).toEditList()
    val type = when (entry.changeType) {
        DiffEntry.ChangeType.ADD, DiffEntry.ChangeType.COPY -> GitChangeType.ADDED
        DiffEntry.ChangeType.DELETE -> GitChangeType.DELETED
        else -> GitChangeType.MODIFIED
    }
    val path = if (type == GitChangeType.DELETED) entry.oldPath else entry.newPath
    return GitCommitFile(path, type, edits.sumOf { it.endB - it.beginB }, edits.sumOf { it.endA - it.beginA })
}
