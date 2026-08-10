package dev.tabcode.sandbox.shell

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

    private companion object {
        const val SHELL = "/system/bin/sh"
        const val SHELL_COMMAND_FLAG = "-c"
    }
}
