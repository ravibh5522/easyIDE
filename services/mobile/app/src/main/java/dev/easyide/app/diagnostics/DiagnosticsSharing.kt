package dev.easyide.app.diagnostics

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/**
 * What leaves the device when the user shares or exports diagnostics: assembly only, the
 * sources are [AppLog] and [CrashReports]. Used by the Diagnostics screen and by the crash
 * recovery dialog, which has no report to render and passes [DiagnosticsReportText.header].
 */
class DiagnosticsSharing(
    private val appLog: AppLog,
    private val crashReports: CrashReports,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** A share-sheet text: [summary], the newest crash report, and the log tail, within [ShareText.SHARE_MAX_CHARS]. */
    suspend fun text(summary: String): String = withContext(io) {
        val crash = try {
            crashReports.latestSeenOrPending()?.let(crashReports::read)
        } catch (gone: IOException) {
            null // a report deleted between listing and reading is simply not shared
        }
        val log = try {
            appLog.readAll().map(LogFormat::encode)
        } catch (unreadable: IOException) {
            emptyList()
        }
        ShareText.build(summary, crash, log)
    }

    /**
     * Writes the whole export to [out]: [summary], every stored crash report, then the raw log.
     * The caller owns [out] and closes it.
     *
     * @throws IOException if the log cannot be read or [out] cannot be written.
     */
    suspend fun export(out: OutputStream, summary: String) = withContext(io) {
        val crashes = crashReports.list().mapNotNull { ref ->
            try {
                ref.fileName to crashReports.read(ref)
            } catch (gone: IOException) {
                null
            }
        }
        LogExport.write(out, summary, crashes, appLog)
    }
}

/** The export file layout. Fixed English section markers: it is read by people and by grep. */
object LogExport {
    const val SUMMARY_MARKER = "=== Summary ==="
    const val CRASH_MARKER = "=== Crash report: "
    const val LOG_MARKER = "=== Log ==="

    fun write(out: OutputStream, summary: String, crashes: List<Pair<String, String>>, log: AppLog) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.appendLine(SUMMARY_MARKER)
        writer.appendLine(summary.trimEnd())
        for ((name, text) in crashes) {
            writer.appendLine()
            writer.appendLine("$CRASH_MARKER$name ===")
            writer.appendLine(text.trimEnd())
        }
        writer.appendLine()
        writer.appendLine(LOG_MARKER)
        // Flush, not close: closing the writer would close [out], which the caller owns.
        writer.flush()
        log.copyTo(out)
        out.flush()
    }
}

/** The bounded text for a share sheet. Intents are size-limited, so it is capped and keeps the newest log. */
object ShareText {
    /** Well under the ~1 MB binder transaction limit even at 4 bytes per character. */
    const val SHARE_MAX_CHARS = 60_000

    private const val LOG_HEADER = "--- Log (newest lines) ---"

    /**
     * [summary] first, then [crashReport] (its beginning: the exception and top frames matter
     * most, and it is clipped to half the budget), then as many of the newest [logLines] as
     * fit. The result is never longer than [maxChars].
     */
    fun build(summary: String, crashReport: String?, logLines: List<String>, maxChars: Int = SHARE_MAX_CHARS): String {
        val head = buildString {
            appendLine(summary.trimEnd().take(maxChars / SUMMARY_SHARE))
            crashReport?.let {
                appendLine()
                appendLine(it.trimEnd().take(maxChars / CRASH_SHARE))
            }
            appendLine()
            appendLine(LOG_HEADER)
        }.take(maxChars)
        var budget = maxChars - head.length
        val kept = ArrayDeque<String>()
        for (line in logLines.asReversed()) {
            if (line.length + 1 > budget) break
            kept.addFirst(line)
            budget -= line.length + 1
        }
        return head + kept.joinToString(separator = "\n", postfix = if (kept.isEmpty()) "" else "\n")
    }

    private const val SUMMARY_SHARE = 4
    private const val CRASH_SHARE = 2
}
