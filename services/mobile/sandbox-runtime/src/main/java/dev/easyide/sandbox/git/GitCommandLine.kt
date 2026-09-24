package dev.easyide.sandbox.git

/** Who authors commits that guest git creates itself (a merge or rebase during `pull`). */
data class GitIdentity(val name: String, val email: String) {
    companion object {
        /**
         * A usable identity, or null when either half is missing or the email
         * has no `@`: git would reject the commit later with a far less helpful
         * message, so the UI asks before that point.
         */
        fun of(name: String?, email: String?): GitIdentity? {
            val n = name?.trim().orEmpty()
            val e = email?.trim().orEmpty()
            if (n.isEmpty() || !EMAIL.matches(e)) return null
            return GitIdentity(n, e)
        }

        private val EMAIL = Regex("[^\\s@<>]+@[^\\s@<>]+")
    }
}

/** How `pull` reconciles local and remote history; [flag] is the git option that selects it. */
enum class PullStrategy(val flag: String) {
    MERGE("--no-rebase"),
    REBASE("--rebase"),
    FF_ONLY("--ff-only"),
}

/** The network operations the UI can start; each becomes one guest git invocation. */
sealed interface GitNetworkOp {
    /** [remote] null fetches every remote, which is what auto-fetch wants. */
    data class Fetch(val remote: String? = null) : GitNetworkOp

    data class Pull(val strategy: PullStrategy, val identity: GitIdentity?) : GitNetworkOp

    /**
     * [setUpstream] publishes a branch that has none yet. [forceWithLease]
     * refuses to overwrite commits the local clone has not seen, which plain
     * `--force` would; it is the only force the UI offers.
     */
    data class Push(
        val remote: String?,
        val branch: String?,
        val setUpstream: Boolean = false,
        val forceWithLease: Boolean = false,
    ) : GitNetworkOp
}

/**
 * Pure builders for the guest git command lines, kept apart from [GitRemote] so
 * quoting and flag choice can be tested without a sandbox.
 */
object GitCommandLine {

    /** Environment variables the inline credential helper reads; see decision 0012. */
    const val TOKEN_ENV = "EASYIDE_GIT_TOKEN"
    const val USER_ENV = "EASYIDE_GIT_USER"

    /**
     * `-c` rather than a config file so nothing is persisted, and a shell
     * function so token and user are expanded by the helper at the moment git
     * asks - never appearing in the command line, which `ps` would show.
     */
    const val CREDENTIAL_HELPER_GIT =
        "git -c credential.helper='!f() { echo username=\$$USER_ENV; echo \"password=\$$TOKEN_ENV\"; }; f'"

    /** Marks a git process as non-interactive so a missing credential fails instead of hanging on a prompt. */
    private const val NO_PROMPT = "GIT_TERMINAL_PROMPT=0"

    /** The sub-command and its arguments, without the leading `git`. */
    fun subcommand(op: GitNetworkOp): String = when (op) {
        is GitNetworkOp.Fetch -> buildString {
            append("fetch --prune --progress")
            append(if (op.remote == null) " --all" else " ${quote(op.remote)}")
        }
        is GitNetworkOp.Pull -> buildString {
            op.identity?.let { append(identityFlags(it)).append(' ') }
            append("pull --progress ${op.strategy.flag}")
        }
        is GitNetworkOp.Push -> buildString {
            append("push --progress")
            if (op.setUpstream) append(" --set-upstream")
            if (op.forceWithLease) append(" --force-with-lease")
            op.remote?.let { append(' ').append(quote(it)) }
            op.branch?.let { append(' ').append(quote(it)) }
        }
    }

    /**
     * The whole shell line: the safe.directory preamble (the project is owned by
     * an app uid the guest does not know), then git with or without the
     * credential helper. [subcommand] is appended verbatim; callers pass only
     * output of [subcommand] or a quoted clone.
     */
    fun shellLine(subcommand: String, withCredentials: Boolean): String {
        val git = if (withCredentials) CREDENTIAL_HELPER_GIT else "git"
        return "git config --global --add safe.directory '*' >/dev/null 2>&1; " +
            "$NO_PROMPT LC_ALL=C $git $subcommand 2>&1"
    }

    fun identityFlags(identity: GitIdentity): String =
        "-c user.name=${quote(identity.name)} -c user.email=${quote(identity.email)}"

    /** POSIX single-quote escaping, safe for any user-typed name, branch or URL. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
