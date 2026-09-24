package dev.easyide.sandbox.git

import dev.easyide.sandbox.LinuxEnvironment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

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
 * git -c credential.helper='!f() { echo username=$EASYIDE_GIT_USER; echo "password=$EASYIDE_GIT_TOKEN"; }; f' ...
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
    ): GitResult<String> = run(environmentId, targetDir, url, onOutput = {}) {
        // Into "." because the bind already puts targetDir at guestPath.
        "clone --progress ${GitCommandLine.quote(url)} ."
    }

    suspend fun pull(projectDir: File, environmentId: String, url: String?): GitResult<String> =
        run(environmentId, projectDir, url, onOutput = {}) { "pull --ff-only" }

    suspend fun push(projectDir: File, environmentId: String, url: String?): GitResult<String> =
        run(environmentId, projectDir, url, onOutput = {}) { "push" }

    suspend fun fetch(projectDir: File, environmentId: String, url: String?): GitResult<String> =
        run(environmentId, projectDir, url, onOutput = {}) { "fetch --all --prune" }

    /**
     * Runs [op], streaming git's progress lines to [onOutput] as they arrive.
     *
     * Cancelling the calling coroutine kills the git process (the stream is
     * torn down with it), so a cancel button is just `job.cancel()`.
     * [url] is the remote's URL, used only to look up its token.
     */
    suspend fun execute(
        op: GitNetworkOp,
        projectDir: File,
        environmentId: String,
        url: String?,
        onOutput: (String) -> Unit = {},
    ): GitResult<String> = run(environmentId, projectDir, url, onOutput) { GitCommandLine.subcommand(op) }

    private suspend fun run(
        environmentId: String,
        dir: File,
        url: String?,
        onOutput: (String) -> Unit,
        command: (String) -> String,
    ): GitResult<String> = withContext(ioDispatcher) {
        // Boundary: process spawn and the guest's output. A cancellation must
        // still propagate - swallowing it would let a cancelled operation
        // report a "failure" the user chose.
        try {
            dir.mkdirs()
            val secret = url?.let { u -> credentials.tokenForUrl(u)?.let { it to credentials.usernameForUrl(u) } }
            val full = GitCommandLine.shellLine(command(dir.absolutePath), withCredentials = secret != null)
            val process = linuxEnvironment.start(
                command = full,
                environmentId = environmentId,
                hostProjectDir = dir,
                extraEnvironment = secret?.let { (token, user) ->
                    mapOf(GitCommandLine.TOKEN_ENV to token, GitCommandLine.USER_ENV to user)
                } ?: emptyMap(),
            )
            val output = StringBuilder()
            val code = process.stream { lines ->
                lines.forEach { output.append(it).append('\n') }
                lines.forEach(onOutput)
            }
            if (code == 0) {
                GitResult.Success(output.toString())
            } else {
                val text = output.toString().takeLast(MAX_ERROR_CHARS).ifBlank { "git exited $code" }
                GitResult.Failure(text, GitFailureClassifier.classify(text))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            GitResult.Failure(failure.message ?: failure::class.java.simpleName)
        }
    }

    private companion object {
        const val MAX_ERROR_CHARS = 2000
    }
}
