package dev.easyide.app.ui.screens.workspace.git

/** What follows a successful commit: nothing, a push (publishing a branch that has no upstream), or a pull then a push. */
enum class PostCommit { NONE, PUSH, SYNC }

/**
 * The four entries of the commit split button. [amend] rewrites HEAD, [then] is the network step after the
 * commit, and [needsRemote] disables the entries that cannot work in a repository without one.
 */
enum class CommitMode(val amend: Boolean, val then: PostCommit) {
    COMMIT(false, PostCommit.NONE),
    AMEND(true, PostCommit.NONE),
    COMMIT_PUSH(false, PostCommit.PUSH),
    COMMIT_SYNC(false, PostCommit.SYNC);

    val needsRemote: Boolean get() = then != PostCommit.NONE
}

/** The changed files between two ends of a comparison, shown as a list whose rows open one file's diff. */
data class GitComparison(
    val baseLabel: String,
    val headLabel: String,
    val base: String,
    val head: String,
    val files: List<dev.easyide.sandbox.git.GitCommitFile>,
)
