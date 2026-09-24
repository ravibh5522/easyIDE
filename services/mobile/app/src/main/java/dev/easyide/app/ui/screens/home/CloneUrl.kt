package dev.easyide.app.ui.screens.home

/** A repository address the clone dialog accepted. */
data class CloneUrl(val url: String, val suggestedName: String) {

    enum class Problem { EMPTY, UNSUPPORTED_SCHEME, MALFORMED }

    sealed interface Result {
        data class Valid(val value: CloneUrl) : Result
        data class Invalid(val problem: Problem) : Result
    }

    companion object {
        private val HTTP_URL = Regex("^https?://[^/\\s]+(/[^\\s]*)?$", RegexOption.IGNORE_CASE)
        private val SSH_LIKE = Regex("^(ssh://|git@|git://|[^/\\s@]+@[^/\\s:]+:)", RegexOption.IGNORE_CASE)

        /**
         * Only http(s) is accepted. SSH needs keys the app cannot manage yet, so
         * offering it would fail after the user waited. Requiring the scheme up
         * front also means the value can never begin with `-`, which git would
         * read as an option rather than a repository.
         */
        fun parse(input: String): Result {
            val text = input.trim()
            return when {
                text.isEmpty() -> Result.Invalid(Problem.EMPTY)
                SSH_LIKE.containsMatchIn(text) -> Result.Invalid(Problem.UNSUPPORTED_SCHEME)
                !HTTP_URL.matches(text) -> Result.Invalid(Problem.MALFORMED)
                else -> Result.Valid(CloneUrl(text, suggestedNameOf(text)))
            }
        }

        /** The repository name: last path segment without `.git` ("https://host/o/api-svc.git" -> "api-svc"). */
        private fun suggestedNameOf(url: String): String =
            url.substringAfter("://").substringAfter('/', "")
                .substringBefore('?').substringBefore('#')
                .trimEnd('/')
                .substringAfterLast('/')
                .removeSuffix(".git")
    }
}

/** Why a clone failed, from git's own output, so the message can say what to do about it. */
enum class CloneFailure { AUTHENTICATION, NOT_FOUND, OTHER }

private val AUTH_MARKERS = listOf(
    "authentication failed", "could not read username", "terminal prompts disabled", "http 401", "http 403",
    "error: 401", "error: 403",
)
private val NOT_FOUND_MARKERS = listOf("repository not found", "does not exist", "http 404", "error: 404")

fun classifyCloneFailure(gitOutput: String): CloneFailure {
    val text = gitOutput.lowercase()
    return when {
        AUTH_MARKERS.any { it in text } -> CloneFailure.AUTHENTICATION
        NOT_FOUND_MARKERS.any { it in text } -> CloneFailure.NOT_FOUND
        else -> CloneFailure.OTHER
    }
}

/** The last meaningful line of git's output, for a failure message that is not a wall of progress text. */
fun lastOutputLine(gitOutput: String): String =
    gitOutput.split('\n', '\r').map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
