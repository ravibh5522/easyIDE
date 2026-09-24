package dev.easyide.sandbox.git

/** One entry of `git stash list`; [index] is the `N` of `stash@{N}`. */
data class GitStashEntry(val index: Int, val message: String, val timestampMillis: Long)

fun GitRepository.stashEntries(): List<GitStashEntry> =
    git.stashList().call().mapIndexed { index, commit ->
        GitStashEntry(index, commit.shortMessage, commit.committerIdent.whenAsInstant.toEpochMilli())
    }

/**
 * Shelves tracked changes (and untracked files when [includeUntracked]) and
 * resets the tree. Returns false when there was nothing to stash.
 */
fun GitRepository.stashPush(message: String?, includeUntracked: Boolean): Boolean {
    val command = git.stashCreate().setIncludeUntracked(includeUntracked)
    message?.takeIf { it.isNotBlank() }?.let { command.setWorkingDirectoryMessage(it) }
    return command.call() != null
}

/** Applies `stash@{index}` without dropping it; [stashPop] is the drop-on-success variant. */
fun GitRepository.stashApply(index: Int) {
    git.stashApply().setStashRef("stash@{$index}").call()
}

/**
 * Apply, then drop. The drop runs only if apply did not throw, so a stash
 * whose changes conflict is kept rather than lost.
 */
fun GitRepository.stashPop(index: Int) {
    stashApply(index)
    stashDrop(index)
}

fun GitRepository.stashDrop(index: Int) {
    git.stashDrop().setStashRef(index).call()
}
