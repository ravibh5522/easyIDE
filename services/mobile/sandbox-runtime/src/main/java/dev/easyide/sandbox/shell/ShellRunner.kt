package dev.easyide.sandbox.shell

import kotlinx.coroutines.CoroutineDispatcher
import java.io.File

data class ShellResult(
    val exitCode: Int,
    val output: String,
)

/**
 * Runs a command through Android's own `/system/bin/sh`, inside the app's
 * sandbox and with the app's UID.
 *
 * This is the real-but-limited shell available before a Linux userland is
 * provisioned: Android's toybox applets work (ls, cat, grep, ps ...), but
 * anything the platform does not ship is absent.
 */
class ShellRunner(private val ioDispatcher: CoroutineDispatcher) {

    /** Starts the command; the caller streams it via [TerminalProcess.stream]. */
    fun start(command: String, workingDir: File): TerminalProcess {
        val process = ProcessBuilder(SHELL, SHELL_COMMAND_FLAG, command)
            .directory(workingDir)
            // Merged so the user sees stderr inline, the way a terminal does.
            .redirectErrorStream(true)
            .start()
        return TerminalProcess(process, ioDispatcher)
    }

    /**
     * The pty-backed counterpart to [start] - see [SandboxShell.interactiveParams].
     *
     * Unlike [start] (a `ProcessBuilder`, which inherits Android's own
     * environment for free), the pty path's native `create_subprocess()`
     * clears the environment before applying exactly what is passed here - so
     * PATH has to be spelled out, or toybox applets the shell tries to run
     * (`ls`, `cat`, ...) come back "not found" even though `/system/bin/sh`
     * itself started fine.
     */
    fun interactiveParams(workingDir: File): PtyShellParams = PtyShellParams(
        shellPath = SHELL,
        args = emptyList(),
        env = mapOf(
            "HOME" to workingDir.absolutePath,
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "PATH" to ANDROID_DEFAULT_PATH,
        ),
        cwd = workingDir.absolutePath,
    )

    private companion object {
        const val SHELL = "/system/bin/sh"
        const val SHELL_COMMAND_FLAG = "-c"
        const val ANDROID_DEFAULT_PATH =
            "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:" +
                "/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"
    }
}
