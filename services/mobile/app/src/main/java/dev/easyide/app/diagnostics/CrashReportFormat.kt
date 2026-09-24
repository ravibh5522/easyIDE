package dev.easyide.app.diagnostics

/**
 * Renders an uncaught exception into the text file a user can share: a fixed header that
 * identifies the build and device, the full stack trace with every cause, then the tail of the
 * app log so the lines leading up to the crash travel with it.
 *
 * Pure: no `android.*`, no I/O, so it is unit-tested and safe to run inside an uncaught
 * exception handler.
 */
object CrashReportFormat {
    const val TITLE = "easyIDE crash report"
    const val EXCEPTION_PREFIX = "Exception: "
    private const val LOG_TAIL_TITLE = "--- Log tail"

    fun render(
        threadName: String,
        throwable: Throwable,
        build: BuildInfo,
        logTail: List<String>,
        nowMs: Long,
    ): String = buildString {
        appendLine(TITLE)
        appendLine("App: ${build.versionName} (code ${build.versionCode})")
        appendLine("Build: ${build.channel}")
        appendLine("Device: ${build.manufacturer} ${build.model}")
        appendLine("Android SDK: ${build.sdkInt}")
        appendLine("ABIs: ${build.abis.joinToString(", ")}")
        appendLine("Time: ${LogFormat.formatTime(nowMs)}")
        appendLine("Thread: $threadName")
        appendLine("$EXCEPTION_PREFIX${throwable.headline()}")
        appendLine()
        appendLine(throwable.stackTraceToString().trimEnd())
        appendLine()
        appendLine("$LOG_TAIL_TITLE (${logTail.size} lines) ---")
        logTail.forEach(::appendLine)
    }

    /**
     * One line for lists and the recovery dialog: the `Exception:` header line of [report], or
     * its first non-blank line for a file this format did not write.
     */
    fun headline(report: String): String {
        val lines = report.lineSequence()
        return lines.firstOrNull { it.startsWith(EXCEPTION_PREFIX) }?.removePrefix(EXCEPTION_PREFIX)
            ?: lines.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun Throwable.headline(): String =
        "${javaClass.name}${message?.let { ": ${it.lineSequence().first()}" }.orEmpty()}"
}
