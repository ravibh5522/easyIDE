package dev.easyide.sandbox.git

/** One stored credential: the token authenticates [username] on [host]. */
data class GitCredential(val host: String, val username: String, val token: String)

/** A stored credential as a settings screen may show it: the token is deliberately absent. */
data class GitCredentialEntry(val host: String, val username: String)

/**
 * The plaintext that [GitCredentials] encrypts: one `host TAB token TAB
 * username` line per host. Pulled out of the Keystore-bound class so the
 * format, and its read-compatibility with files written before usernames
 * existed (`host TAB token`), can be tested on the JVM.
 */
internal object GitCredentialsFormat {

    /**
     * What git is told when the user gave no username. Forges that accept a
     * personal access token as the password (GitHub, GitLab) ignore the name;
     * this is the value the credential helper always used before usernames were
     * configurable.
     */
    const val DEFAULT_USERNAME = "x"

    fun encode(entries: Collection<GitCredential>): String =
        entries.joinToString("\n") { "${it.host}\t${it.token}\t${it.username}" }

    fun decode(plain: String): Map<String, GitCredential> =
        plain.lineSequence()
            .filter { it.contains('\t') }
            .map { line ->
                val parts = line.split('\t', limit = 3)
                GitCredential(
                    host = parts[0],
                    token = parts[1],
                    username = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: DEFAULT_USERNAME,
                )
            }
            .associateBy { it.host }

    /** Tabs and newlines would corrupt the line format, and a blank token authenticates nothing. */
    fun isStorable(host: String, token: String, username: String): Boolean =
        host.isNotBlank() && token.isNotBlank() &&
            listOf(host, token, username).none { v -> v.any { it == '\t' || it == '\n' || it == '\r' } }
}

/**
 * Extracts the host a token applies to. Kept out of [GitCredentials] so it is
 * usable (and testable) without an Android context.
 */
object GitUrl {
    /**
     * `https://github.com/user/repo.git` -> `github.com`; also accepts a bare
     * host the user typed into the credentials form. Null for anything else,
     * notably scp-style `git@host:path` URLs, which are SSH and carry no token.
     */
    fun host(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val parsed = runCatching { java.net.URI(text).host }.getOrNull()
        if (parsed != null) return parsed.lowercase()
        return text.lowercase().takeIf { HOST.matches(it) }
    }

    private val HOST = Regex("[a-z0-9]([a-z0-9.-]*[a-z0-9])?")
}
