package dev.easyide.sandbox.git

import dev.easyide.sandbox.LinuxEnvironment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Network git - clone, fetch, pull, push - run as the guest's real `git`.
 *
 * Deliberately not JGit: the sandbox is where the user's `.gitconfig`, hooks,
 * LFS and submodule support already live, and real git is the only thing
 * guaranteed to agree with the server about every one of them.
 *
 * ### How the token reaches git
 *
 * Through the **environment of that one process**, read by an inline credential
 * helper:
 *
 * ```
 * git -c credential.helper='!f() { echo username=x; echo "password=$EASYIDE_GIT_TOKEN"; }; f' ...
 * ```
 *
 * This is the resolution of the open question in decision 0009. The obvious
 * alternative - the credential-helper daemon on a unix socket in the rootfs -
 * is reachable by *anything* running in the sandbox, and once push works the
 * thing behind it is a token that can write to the user's repositories. Passing
 * it in one process's environment instead means:
 *
 *  - it is never written to the rootfs, so it cannot be read back later;
 *  - it never enters the remote URL, so it stays out of `.git/config`, the
 *    reflog and any error message that echoes the URL;
 *  - its lifetime is the lifetime of that single git invocation.
 *
 * It is still readable via `/proc/<pid>/environ` by another process in the same
 * sandbox during that window. proot is not a boundary (decision 0002), so no
 * arrangement here is airtight - this one is narrow rather than ambient.
 */
class GitRemote(
    private val linuxEnvironment: LinuxEnvironment,
    private val credentials: GitCredentials,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Clones [url] into [targetDir] on the host, which the sandbox sees at its
     * bound guest path. Returns git's combined output either way, because a
     * clone failure is something the user has to read, not a boolean.
     */
    suspend fun clone(
        url: String,
        targetDir: File,
        environmentId: String,
    ): GitResult<String> = run(environmentId, targetDir, url) { guestPath ->
        // Into "." because the bind already puts targetDir at guestPath.
        "clone --progress ${quote(url)} ."
    }

    suspend fun pull(projectDir: File, environmentId: String, url: String?): GitResult<String> =
        run(environmentId, projectDir, url) { "pull --ff-only" }

    suspend fun push(projectDir: File, environmentId: String, url: String?): GitResult<String> =
        run(environmentId, projectDir, url) { "push" }

    suspend fun fetch(projectDir: File, environmentId: String, url: String?): GitResult<String> =
        run(environmentId, projectDir, url) { "fetch --all --prune" }

    private suspend fun run(
        environmentId: String,
        dir: File,
        url: String?,
        command: (String) -> String,
    ): GitResult<String> = withContext(ioDispatcher) {
        runCatching {
            dir.mkdirs()
            val token = url?.let { credentials.tokenForUrl(it) }
            val subcommand = command(dir.absolutePath)
            val invocation = if (token != null) CREDENTIAL_HELPER_GIT else "git"
            val full = "git config --global --add safe.directory '*' >/dev/null 2>&1; " +
                "$invocation $subcommand 2>&1"
            val process = linuxEnvironment.start(
                command = full,
                environmentId = environmentId,
                hostProjectDir = dir,
                extraEnvironment = token?.let { mapOf(TOKEN_ENV to it) } ?: emptyMap(),
            )
            val output = StringBuilder()
            val code = process.stream { lines -> lines.forEach { output.append(it).append('\n') } }
            if (code == 0) {
                GitResult.Success(output.toString())
            } else {
                GitResult.Failure(output.toString().takeLast(MAX_ERROR_CHARS).ifBlank { "git exited $code" })
            }
        }.getOrElse { GitResult.Failure(it.message ?: it::class.java.simpleName) }
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val TOKEN_ENV = "EASYIDE_GIT_TOKEN"
        const val MAX_ERROR_CHARS = 2000

        /**
         * `-c` rather than a config file so nothing is persisted, and a shell
         * function so the token is expanded by the helper at the moment git
         * asks - never appearing in the command line, which `ps` would show.
         */
        const val CREDENTIAL_HELPER_GIT =
            "git -c credential.helper='!f() { echo username=x; echo \"password=\$$TOKEN_ENV\"; }; f'"
    }
}
