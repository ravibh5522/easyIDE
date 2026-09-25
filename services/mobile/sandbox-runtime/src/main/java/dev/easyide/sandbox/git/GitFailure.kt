package dev.easyide.sandbox.git

/**
 * Why a git operation failed, as far as the UI needs to tell apart. Each kind
 * has its own explanation or recovery action (a deep link to credentials for
 * [AUTH], a "pull first" hint for [NON_FAST_FORWARD]); everything else is shown
 * as git's own words under [UNKNOWN].
 */
enum class GitFailureKind {
    AUTH,
    NON_FAST_FORWARD,
    NO_UPSTREAM,
    DIVERGED,
    MERGE_CONFLICT,
    DIRTY_TREE,
    UNMERGED_BRANCH,
    NETWORK,
    UNKNOWN,
}

/**
 * Maps guest-git output to a [GitFailureKind]. Git has no machine-readable
 * error channel, so this matches the stable English messages (the guest runs
 * with a C locale, see [GitCommandLine]); an unrecognised message degrades to
 * [GitFailureKind.UNKNOWN] and is still shown verbatim.
 *
 * Order matters: an HTTP 403 arrives wrapped in "unable to access", which is
 * also the network pattern, so authentication is tested first.
 */
object GitFailureClassifier {

    private val rules: List<Pair<GitFailureKind, List<String>>> = listOf(
        GitFailureKind.AUTH to listOf(
            "authentication failed",
            "could not read username",
            "could not read password",
            "permission denied (publickey",
            "invalid username or password",
            "invalid username or token",
            "http basic: access denied",
            "returned error: 401",
            "returned error: 403",
            "terminal prompts disabled",
        ),
        GitFailureKind.NON_FAST_FORWARD to listOf(
            "non-fast-forward",
            "updates were rejected because",
            "tip of your current branch is behind",
            "failed to push some refs",
        ),
        GitFailureKind.NO_UPSTREAM to listOf(
            "has no upstream branch",
            "no tracking information for the current branch",
            "no upstream configured",
        ),
        GitFailureKind.DIVERGED to listOf(
            "not possible to fast-forward",
            "divergent branches",
        ),
        GitFailureKind.MERGE_CONFLICT to listOf(
            "conflict (",
            "automatic merge failed",
            "could not apply",
        ),
        GitFailureKind.DIRTY_TREE to listOf(
            "would be overwritten by",
            "please commit your changes or stash them",
            "you have unstaged changes",
            "cannot pull with rebase",
        ),
        GitFailureKind.NETWORK to listOf(
            "could not resolve host",
            "unable to access",
            "connection timed out",
            "connection refused",
            "failed to connect",
            "network is unreachable",
            "early eof",
        ),
    )

    fun classify(output: String): GitFailureKind {
        val lower = output.lowercase()
        return rules.firstOrNull { (_, needles) -> needles.any { lower.contains(it) } }?.first
            ?: GitFailureKind.UNKNOWN
    }
}
