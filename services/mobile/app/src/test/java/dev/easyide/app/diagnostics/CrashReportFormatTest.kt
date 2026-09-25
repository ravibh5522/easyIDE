package dev.easyide.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportFormatTest {
    private val error = IllegalStateException("outer failed", java.io.IOException("disk gone"))

    private fun report(tail: List<String> = listOf("line one", "line two")) =
        CrashReportFormat.render("main", error, testBuildInfo(), tail, nowMs = 0)

    @Test fun `the header identifies the build and the device`() {
        val text = report()
        assertTrue(text, text.startsWith("easyIDE crash report\n"))
        assertTrue(text.contains("App: 1.2.3-debug (code 42)\n"))
        assertTrue(text.contains("Build: debug\n"))
        assertTrue(text.contains("Device: Acme Tab 9\n"))
        assertTrue(text.contains("Android SDK: 34\n"))
        assertTrue(text.contains("ABIs: x86_64\n"))
        assertTrue(text.contains("Time: 1970-01-01T00:00:00.000Z\n"))
        assertTrue(text.contains("Thread: main\n"))
    }

    @Test fun `a build without a suffix is the release channel`() {
        assertEquals(BuildInfo.RELEASE_CHANNEL, testBuildInfo("2.0.0").channel)
        assertEquals("canary.7+abc1234", testBuildInfo("2.0.0-canary.7+abc1234").channel)
    }

    @Test fun `the full stack trace includes every cause`() {
        val text = report()
        assertTrue(text.contains("java.lang.IllegalStateException: outer failed"))
        assertTrue(text.contains("Caused by: java.io.IOException: disk gone"))
        assertTrue(text.contains("CrashReportFormatTest"))
    }

    @Test fun `the log tail follows the trace`() {
        val text = report(listOf("alpha", "beta"))
        assertTrue(text.contains("--- Log tail (2 lines) ---\nalpha\nbeta\n"))
        assertTrue(text.indexOf("Caused by") < text.indexOf("--- Log tail"))
    }

    @Test fun `an empty tail is stated`() {
        assertTrue(report(emptyList()).contains("--- Log tail (0 lines) ---"))
    }

    @Test fun `the headline is the exception line, first line of a multi-line message only`() {
        val multi = RuntimeException("first\nsecond")
        val text = CrashReportFormat.render("t", multi, testBuildInfo(), emptyList(), 0)
        assertEquals("java.lang.RuntimeException: first", CrashReportFormat.headline(text))
        assertEquals("java.lang.IllegalStateException: outer failed", CrashReportFormat.headline(report()))
    }

    @Test fun `a throwable without a message has just its class`() {
        val text = CrashReportFormat.render("t", NullPointerException(), testBuildInfo(), emptyList(), 0)
        assertEquals("java.lang.NullPointerException", CrashReportFormat.headline(text))
    }

    @Test fun `headline of a foreign file is its first non-blank line`() {
        assertEquals("something else", CrashReportFormat.headline("\n\nsomething else\nmore"))
        assertEquals("", CrashReportFormat.headline(""))
    }
}
