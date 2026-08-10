package dev.tabcode.app.ui.screens.workspace

import androidx.compose.ui.text.input.TextFieldValue
import dev.tabcode.sandbox.shell.TerminalProcess
import kotlinx.coroutines.Job

data class TerminalLine(val text: String, val isCommand: Boolean)

/**
 * One terminal tab. Each has its own scrollback, its own in-flight command and
 * its own history, so switching tabs does not disturb a running build.
 *
 * [input] carries its caret, not just its text: the accessory key row inserts
 * at the caret, and recalling from history has to land the caret at the end.
 */
data class TerminalSession(
    val id: String,
    val title: String,
    val lines: List<TerminalLine> = emptyList(),
    val input: TextFieldValue = TextFieldValue(),
    val isRunning: Boolean = false,
    val history: List<String> = emptyList(),
    /** Position when stepping back through [history]; null means "at the prompt". */
    val historyCursor: Int? = null,
) {
    fun appended(newLines: List<TerminalLine>, maxLines: Int): TerminalSession =
        copy(lines = (lines + newLines).takeLast(maxLines))
}

/**
 * Live processes and their streaming jobs, held outside the UI state: neither a
 * Job nor a Process is equatable, so putting them in state would defeat
 * recomposition skipping.
 */
class TerminalJobs {
    private val jobs = mutableMapOf<String, Job>()
    private val processes = mutableMapOf<String, TerminalProcess>()

    fun put(sessionId: String, job: Job, process: TerminalProcess) {
        jobs[sessionId] = job
        processes[sessionId] = process
    }

    /** The running process for a session, if it is still alive. */
    fun processFor(sessionId: String): TerminalProcess? =
        processes[sessionId]?.takeIf { it.isAlive }

    fun finish(sessionId: String) {
        jobs.remove(sessionId)
        processes.remove(sessionId)
    }

    fun cancel(sessionId: String) {
        processes.remove(sessionId)?.kill()
        jobs.remove(sessionId)?.cancel()
    }

    fun cancelAll() {
        processes.values.forEach { it.kill() }
        jobs.values.forEach { it.cancel() }
        processes.clear()
        jobs.clear()
    }
}
