package dev.easyide.sandbox.shell

import dev.easyide.sandbox.LinuxEnvironment
import dev.easyide.sandbox.backend.GuestEnvironment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * One language-server launch (docs/extension-sdk/lld/lsp-client.md sec 3.2).
 *
 * @property argv guest argv with variables already expanded; `argv[0]` is resolved on the
 *   guest `PATH`.
 * @property env the server's declared `env` only - never anything from the app process.
 */
data class ServerLaunch(
    val environmentId: String,
    val hostProjectDir: File,
    val argv: List<String>,
    val env: Map<String, String>,
) {
    init {
        require(argv.isNotEmpty()) { "server argv must not be empty" }
    }
}

/**
 * A running server: proot (with `--kill-on-exit`) whose guest child is the server itself.
 * Stopping proot therefore stops the server. The three streams are separate plain pipes.
 *
 * There is deliberately no pid: `java.lang.Process` on Android exposes none at any API
 * level, so RSS sampling of the process tree needs a separate mechanism.
 */
class ServerProcess internal constructor(
    private val process: Process,
    private val ioDispatcher: CoroutineDispatcher,
) {
    val stdin: OutputStream get() = process.outputStream
    val stdout: InputStream get() = process.inputStream
    val stderr: InputStream get() = process.errorStream
    val isAlive: Boolean get() = process.isAlive

    /** SIGTERM. */
    fun terminate() = process.destroy()

    /** SIGKILL. */
    fun kill() {
        process.destroyForcibly()
    }

    /** Suspends until the process exits; cancellation interrupts the wait, not the process. */
    suspend fun awaitExit(): Int = runInterruptible(ioDispatcher) { process.waitFor() }
}

/**
 * The single place language servers are spawned (rule R-ENG-14): a non-pty stdio process in
 * an environment, cwd `/workspace`, with a clean environment plus the declared `env` - the
 * same proot preparation and extension binds as a terminal, via
 * [LinuxEnvironment.startPiped].
 *
 * Servers run with the user's permissions inside the environment; proot does not contain
 * them (decision 0002).
 */
class ServerProcessFactory(
    private val linuxEnvironment: LinuxEnvironment,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * @throws dev.easyide.sandbox.SandboxError.EnvironmentNotReady if the environment has no rootfs.
     * @throws IOException if proot cannot be started.
     */
    suspend fun spawn(launch: ServerLaunch): ServerProcess {
        // `exec "$0" "$@"`: argv reaches the guest as positional parameters, so nothing is
        // re-quoted or word-split, `argv[0]` is found on the guest PATH, and `exec` makes
        // the server replace the shell - one guest process, whose exit is proot's exit.
        val command = guestArgv(launch.argv)
        val process = linuxEnvironment.startPiped(
            environmentId = launch.environmentId,
            hostProjectDir = launch.hostProjectDir,
            command = command,
            extraEnvironment = mapOf(GuestEnvironment.TERM to SERVER_TERM) + launch.env,
        )
        return ServerProcess(process, ioDispatcher)
    }

    /**
     * Whether [commandName] resolves on the guest `PATH` (`command -v`), used for the
     * NOT_INSTALLED state before any spawn is attempted.
     *
     * @throws dev.easyide.sandbox.SandboxError.EnvironmentNotReady if the environment has no rootfs.
     * @throws IOException if proot cannot be started.
     */
    suspend fun isInstalled(environmentId: String, hostProjectDir: File, commandName: String): Boolean {
        val process = linuxEnvironment.startPiped(
            environmentId = environmentId,
            hostProjectDir = hostProjectDir,
            command = listOf(GUEST_SHELL, GUEST_SHELL_FLAG, PROBE_COMMAND, commandName),
            extraEnvironment = mapOf(GuestEnvironment.TERM to SERVER_TERM),
        )
        return runInterruptible(ioDispatcher) {
            process.outputStream.close()
            val exit = process.waitFor()
            process.inputStream.close()
            process.errorStream.close()
            exit == 0
        }
    }

    companion object {
        /**
         * The guest command a server's [argv] becomes, verbatim as it ends the launcher's
         * argv - so the app can recognise the server's host process in `/proc` (Android's
         * `Process` has no pid) by the same list rather than a re-derived copy.
         */
        fun guestArgv(argv: List<String>): List<String> = listOf(GUEST_SHELL, GUEST_SHELL_FLAG, EXEC_ARGV) + argv

        private const val GUEST_SHELL = "/bin/sh"
        private const val GUEST_SHELL_FLAG = "-c"
        private const val EXEC_ARGV = "exec \"\$0\" \"\$@\""

        /** Output goes to /dev/null in the guest, so the unread pipes can never fill up. */
        private const val PROBE_COMMAND = "command -v \"\$0\" >/dev/null 2>&1"

        /** Servers are not terminals; `dumb` stops tools from emitting colour escapes. */
        private const val SERVER_TERM = "dumb"
    }
}
