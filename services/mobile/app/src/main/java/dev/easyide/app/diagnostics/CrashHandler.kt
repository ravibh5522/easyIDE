package dev.easyide.app.diagnostics

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns an uncaught exception into a stored [CrashReports] entry, then hands over to the
 * previous handler so Android still shows its "app stopped" behaviour and kills the process
 * exactly as it would without us.
 */
object CrashHandler {
    /** Lines of the app log copied into a report: enough context, small enough to share. */
    const val LOG_TAIL_LINES = 200

    fun install(
        reports: CrashReports,
        log: AppLog,
        build: BuildInfo,
        previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler(),
        clock: () -> Long = System::currentTimeMillis,
    ) {
        Thread.setDefaultUncaughtExceptionHandler(handler(reports, log, build, previous, clock))
    }

    internal fun handler(
        reports: CrashReports,
        log: AppLog,
        build: BuildInfo,
        previous: Thread.UncaughtExceptionHandler?,
        clock: () -> Long,
    ): Thread.UncaughtExceptionHandler {
        // A second crash while writing the report (or on another thread at the same moment)
        // must not start a second report: it would only race the first for the same disk.
        val busy = AtomicBoolean(false)
        return Thread.UncaughtExceptionHandler { thread, error ->
            if (busy.compareAndSet(false, true)) {
                try {
                    record(reports, log, build, thread, error, clock)
                } catch (failure: Throwable) {
                    // A crash handler that throws hides the original crash. Nothing here may escape.
                } finally {
                    busy.set(false)
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(
        reports: CrashReports,
        log: AppLog,
        build: BuildInfo,
        thread: Thread,
        error: Throwable,
        clock: () -> Long,
    ) {
        log.log(LogLevel.ERROR, LogSource.APP, "Uncaught exception on thread ${thread.name}: $error")
        val tail = log.tail(LOG_TAIL_LINES).map(LogFormat::encode)
        reports.write(CrashReportFormat.render(thread.name, error, build, tail, clock()), clock())
    }
}
