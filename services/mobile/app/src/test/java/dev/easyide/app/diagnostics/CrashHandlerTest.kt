package dev.easyide.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashHandlerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val build = testBuildInfo()

    private class RecordingHandler : Thread.UncaughtExceptionHandler {
        val calls = mutableListOf<Pair<Thread, Throwable>>()
        override fun uncaughtException(t: Thread, e: Throwable) {
            calls += t to e
        }
    }

    @Test fun `a crash writes a report with the log tail and then delegates to the previous handler`() {
        val log = AppLog(File(tmp.root, "logs")) { 5 }
        log.log(LogLevel.INFO, LogSource.SANDBOX, "before the crash")
        val reports = CrashReports(File(tmp.root, "crashes"))
        val previous = RecordingHandler()
        val error = IllegalStateException("boom")
        val thread = Thread.currentThread()

        CrashHandler.handler(reports, log, build, previous) { 1234 }.uncaughtException(thread, error)

        val ref = reports.pending()
        assertNotNull(ref)
        val text = reports.read(ref!!)
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
        assertTrue(text.contains("Thread: ${thread.name}"))
        assertTrue(text.contains("before the crash"))
        assertTrue(text.contains("Uncaught exception on thread"))
        assertEquals(1234L, ref.atMs)
        assertEquals(1, previous.calls.size)
        assertSame(error, previous.calls.single().second)
        assertSame(thread, previous.calls.single().first)
    }

    @Test fun `the crash is also logged`() {
        val log = AppLog(File(tmp.root, "logs"))
        CrashHandler.handler(CrashReports(File(tmp.root, "crashes")), log, build, null) { 1 }
            .uncaughtException(Thread.currentThread(), RuntimeException("x"))
        assertEquals(LogLevel.ERROR, log.readAll().single().level)
    }

    @Test fun `a failure while writing the report never hides the crash from the previous handler`() {
        val brokenReports = CrashReports(tmp.newFile("a-file-not-a-directory"))
        val previous = RecordingHandler()
        val error = RuntimeException("original")

        CrashHandler.handler(brokenReports, AppLog(File(tmp.root, "logs")), build, previous) { 1 }
            .uncaughtException(Thread.currentThread(), error)

        assertSame(error, previous.calls.single().second)
    }

    @Test fun `a throwing clock is contained`() {
        val previous = RecordingHandler()
        CrashHandler.handler(CrashReports(File(tmp.root, "crashes")), AppLog(File(tmp.root, "logs")), build, previous) {
            throw IllegalStateException("clock broke")
        }.uncaughtException(Thread.currentThread(), RuntimeException("x"))
        assertEquals(1, previous.calls.size)
    }

    @Test fun `without a previous handler the crash is still recorded`() {
        val reports = CrashReports(File(tmp.root, "crashes"))
        CrashHandler.handler(reports, AppLog(File(tmp.root, "logs")), build, null) { 7 }
            .uncaughtException(Thread.currentThread(), RuntimeException("x"))
        assertNotNull(reports.pending())
    }

    @Test fun `the handler keeps working for a second crash`() {
        val reports = CrashReports(File(tmp.root, "crashes"))
        val previous = RecordingHandler()
        val handler = CrashHandler.handler(reports, AppLog(File(tmp.root, "logs")), build, previous) { 10 }
        handler.uncaughtException(Thread.currentThread(), RuntimeException("one"))
        handler.uncaughtException(Thread.currentThread(), RuntimeException("two"))
        assertEquals(2, previous.calls.size)
        assertEquals(2, reports.list().size)
    }
}
