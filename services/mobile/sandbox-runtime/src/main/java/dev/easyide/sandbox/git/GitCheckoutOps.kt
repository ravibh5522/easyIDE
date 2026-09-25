package dev.easyide.sandbox.git

import org.eclipse.jgit.api.CherryPickResult
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter

/**
 * Detaches HEAD at the commit [rev] names. A local edit the checkout would
 * overwrite makes JGit throw a checkout conflict, which the service types as
 * a dirty tree.
 */
fun GitRepository.checkoutDetached(rev: String) {
    val id = repository.resolve("$rev^{commit}") ?: throw IllegalArgumentException("Unknown revision: $rev")
    git.checkout().setName(id.name).call()
}

/**
 * Applies the commit [rev] onto HEAD as a new commit by the given author and
 * returns its id. A conflict is left in the tree, not rolled back, so the
 * merge-state banner and the conflict list can take over; the exception names
 * the files. A merge commit is refused because it has no single parent to
 * diff against.
 */
fun GitRepository.cherryPick(rev: String, authorName: String, authorEmail: String): String {
    val id = repository.resolve("$rev^{commit}") ?: throw IllegalArgumentException("Unknown revision: $rev")
    val commit = RevWalk(repository).use { it.parseCommit(id) }
    require(commit.parentCount <= 1) { "Cannot cherry-pick a merge commit" }
    val result = git.cherryPick().include(commit).setNoCommit(false).call()
    check(result.status == CherryPickResult.CherryPickStatus.OK) {
        when (result.status) {
            CherryPickResult.CherryPickStatus.CONFLICTING ->
                "Cherry-pick has conflicts in: " + conflictedPaths().joinToString(", ")
            else -> "Cherry-pick failed: ${result.status}"
        }
    }
    return result.newHead.name
}

private fun GitRepository.conflictedPaths(): List<String> = git.status().call().conflicting.sorted()

/** The best common ancestor of two revisions as a full id; null when either is unknown or they share no history. */
fun GitRepository.mergeBase(a: String, b: String): String? {
    val left = repository.resolve("$a^{commit}") ?: return null
    val right = repository.resolve("$b^{commit}") ?: return null
    return RevWalk(repository).use { walk ->
        walk.revFilter = RevFilter.MERGE_BASE
        walk.markStart(walk.parseCommit(left))
        walk.markStart(walk.parseCommit(right))
        walk.next()?.name
    }
}

/** The files that differ between two revisions, as [commitDetail] lists them for one commit. */
fun GitRepository.changedBetween(base: String, head: String): List<GitCommitFile> =
    changedFiles(DiffEnd.Rev(base), DiffEnd.Rev(head))
