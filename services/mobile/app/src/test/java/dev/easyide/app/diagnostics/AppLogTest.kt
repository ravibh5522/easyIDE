package dev.easyide.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `lines are stamped with the injected clock`() {
        var now = 1_000L
        val log = AppLog(File(tmp.root, "logs")) { now.also { now += 5 } }
        log.log(LogLevel.INFO, LogSource.APP, "one")
        log.log(LogLevel.ERROR, LogSource.SANDBOX, "two")
        assertEquals(
            listOf(LogLine(1_000, LogLevel.INFO, LogSource.APP, "one"), LogLine(1_005, LogLevel.ERROR, LogSource.SANDBOX, "two")),
            log.readAll(),
        )
    }

    @Test fun `errors returns the newest warnings and errors only`() {
        val log = AppLog(File(tmp.root, "logs"))
        log.log(LogLevel.INFO, LogSource.APP, "info")
        log.log(LogLevel.WARN, LogSource.LSP, "w1")
        log.log(LogLevel.DEBUG, LogSource.APP, "debug")
        log.log(LogLevel.ERROR, LogSource.EXTENSION, "e1")
        log.log(LogLevel.WARN, LogSource.APP, "w2")
        assertEquals(listOf("w1", "e1", "w2"), log.errors(10).map { it.message })
        assertEquals(listOf("e1", "w2"), log.errors(2).map { it.message })
        assertEquals(emptyList<LogLine>(), log.errors(0))
    }

    @Test fun `logging into an unwritable location never throws`() {
        val blocker = tmp.newFile("not-a-directory")
        val log = AppLog(blocker)
        log.log(LogLevel.ERROR, LogSource.APP, "lost, and that is fine")
        log.log(LogLevel.ERROR, LogSource.APP, "still no exception")
    }

    @Test fun `clear empties the log and later lines start a new one`() {
        val log = AppLog(File(tmp.root, "logs"))
        log.log(LogLevel.INFO, LogSource.APP, "old")
        log.clear()
        assertEquals(0L, log.sizeBytes())
        log.log(LogLevel.INFO, LogSource.APP, "new")
        assertEquals(listOf("new"), log.readAll().map { it.message })
    }
}
