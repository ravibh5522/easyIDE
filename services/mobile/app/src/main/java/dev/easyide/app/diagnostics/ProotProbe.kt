package dev.easyide.app.diagnostics

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Why the proot binary could not report a version. The UI maps each to its own string. */
enum class ProotFailure { MISSING, TIMED_OUT, EXIT_CODE, NO_OUTPUT, START_FAILED }

sealed interface ProotStatus {
    val path: String

    data class Found(override val path: String, val version: String) : ProotStatus

    /** [detail] is raw diagnostic text (an exit code with output, an exception message), shown as-is. */
    data class Unavailable(override val path: String, val failure: ProotFailure, val detail: String? = null) : ProotStatus
}

/**
 * Asks the bundled `libproot.so` for its version by running it with `--version`: the only
 * honest way to know the binary on this device starts at all, and which build it is.
 *
 * Running a process is a real boundary, so every failure becomes [ProotStatus.Unavailable]
 * and [probe] never throws. The child is bounded by [timeoutMs] and killed on expiry, so a
 * wedged binary cannot hang the Diagnostics screen. Output is read after the process ends:
 * `--version` prints a few lines, far below the pipe buffer, so waiting first cannot deadlock.
 */
class ProotProbe(private val timeoutMs: Long = TIMEOUT_MS) {

    fun probe(binary: File): ProotStatus {
        val path = binary.path
        if (!binary.isFile) return ProotStatus.Unavailable(path, ProotFailure.MISSING)
        var process: Process? = null
        return try {
            process = ProcessBuilder(path, VERSION_FLAG).redirectErrorStream(true).start()
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                return ProotStatus.Unavailable(path, ProotFailure.TIMED_OUT)
            }
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            val exit = process.exitValue()
            val version = parseVersion(output)
            when {
                exit != 0 -> ProotStatus.Unavailable(path, ProotFailure.EXIT_CODE, "exit $exit${version?.let { ": $it" }.orEmpty()}")
                version == null -> ProotStatus.Unavailable(path, ProotFailure.NO_OUTPUT)
                else -> ProotStatus.Found(path, version)
            }
        } catch (failure: IOException) {
            ProotStatus.Unavailable(path, ProotFailure.START_FAILED, failure.message)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            ProotStatus.Unavailable(path, ProotFailure.START_FAILED, interrupted.message)
        } finally {
            process?.destroyForcibly()
        }
    }

    companion object {
        const val TIMEOUT_MS = 3_000L
        private const val VERSION_FLAG = "--version"
        private const val MAX_VERSION_CHARS = 120

        /** The first non-blank line, trimmed and bounded; null when there is none. */
        fun parseVersion(output: String): String? =
            output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(MAX_VERSION_CHARS)
    }
}
