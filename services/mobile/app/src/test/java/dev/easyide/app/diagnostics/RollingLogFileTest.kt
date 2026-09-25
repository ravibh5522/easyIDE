package dev.easyide.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class RollingLogFileTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Every test line encodes to the same length, so a cap of N lines is exact. */
    private val lineBytes = (LogFormat.encode(logLine(0)) + "\n").length.toLong()

    private fun log(cap: Long = 3 * lineBytes) = RollingLogFile(File(tmp.root, "logs"), cap)

    private fun messages(log: RollingLogFile) = log.readAll().map { it.message }

    @Test fun `lines read back oldest first`() {
        val log = log()
        (0..2).forEach { log.append(logLine(it)) }
        assertEquals(listOf("m000", "m001", "m002"), messages(log))
    }

    @Test fun `an empty log reads as empty`() {
        val log = log()
        assertEquals(emptyList<LogLine>(), log.readAll())
        assertEquals(0L, log.sizeBytes())
        assertEquals(emptyList<LogLine>(), log.tail(5))
    }

    @Test fun `the current segment rotates when the next line would exceed the cap`() {
        val log = log()
        (0..3).forEach { log.append(logLine(it)) }
        val dir = File(tmp.root, "logs")
        assertEquals(3, File(dir, RollingLogFile.PREVIOUS).readLines().size)
        assertEquals(1, File(dir, RollingLogFile.CURRENT).readLines().size)
        assertEquals(listOf("m000", "m001", "m002", "m003"), messages(log))
    }

    @Test fun `the oldest segment is dropped and the size stays bounded`() {
        val log = log()
        (0 until 100).forEach {
            log.append(logLine(it))
            assertTrue(log.sizeBytes() <= 2 * 3 * lineBytes)
        }
        val kept = messages(log)
        assertEquals("m099", kept.last())
        assertTrue(kept.size in 4..6)
        assertEquals(kept.sorted(), kept)
    }

    @Test fun `tail returns the newest lines`() {
        val log = log()
        (0 until 8).forEach { log.append(logLine(it)) }
        assertEquals(listOf("m006", "m007"), log.tail(2).map { it.message })
        assertEquals(emptyList<LogLine>(), log.tail(0))
        assertEquals(messages(log), log.tail(1000).map { it.message })
    }

    @Test fun `a torn last line after a crash is skipped and does not swallow the next entry`() {
        val dir = File(tmp.root, "logs").apply { mkdirs() }
        val good = LogFormat.encode(logLine(1))
        File(dir, RollingLogFile.CURRENT).writeText("$good\n1970-01-01T00:00:00.002Z I/ap")
        val log = RollingLogFile(dir)
        log.append(logLine(3))
        assertEquals(listOf("m001", "m003"), messages(log))
        assertEquals(3, File(dir, RollingLogFile.CURRENT).readLines().size)
    }

    @Test fun `a torn line that still decodes is kept as a shorter message`() {
        val dir = File(tmp.root, "logs").apply { mkdirs() }
        File(dir, RollingLogFile.CURRENT).writeText("1970-01-01T00:00:00.002Z I/app half a mess")
        val log = RollingLogFile(dir)
        log.append(logLine(3))
        assertEquals(listOf("half a mess", "m003"), messages(log))
    }

    @Test fun `reopening continues the same segment and counts its size`() {
        val first = log()
        (0..1).forEach { first.append(logLine(it)) }
        val second = log()
        second.append(logLine(2))
        second.append(logLine(3))
        assertEquals(listOf("m000", "m001", "m002", "m003"), messages(second))
        assertTrue(File(tmp.root, "logs/${RollingLogFile.PREVIOUS}").isFile)
    }

    @Test fun `clear removes both segments and writing resumes`() {
        val log = log()
        (0 until 5).forEach { log.append(logLine(it)) }
        log.clear()
        assertEquals(0L, log.sizeBytes())
        assertEquals(emptyList<LogLine>(), log.readAll())
        log.append(logLine(9))
        assertEquals(listOf("m009"), messages(log))
    }

    @Test fun `copyTo writes the previous segment then the current one`() {
        val log = log()
        (0 until 5).forEach { log.append(logLine(it)) }
        val out = ByteArrayOutputStream()
        log.copyTo(out)
        val lines = out.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        assertEquals(log.readAll().map(LogFormat::encode), lines)
        assertEquals(log.sizeBytes(), out.size().toLong())
    }

    @Test fun `copyTo of an empty log writes nothing`() {
        val out = ByteArrayOutputStream()
        log().copyTo(out)
        assertEquals(0, out.size())
    }

    @Test fun `concurrent writers never interleave lines`() {
        val log = RollingLogFile(File(tmp.root, "logs"))
        val threads = 4
        val perThread = 50
        val start = CountDownLatch(1)
        val workers = (0 until threads).map { t ->
            thread {
                start.await()
                repeat(perThread) { n -> log.append(LogLine(0, LogLevel.INFO, LogSource.APP, "t$t-n$n")) }
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        val read = messages(log)
        assertEquals(threads * perThread, read.size)
        assertEquals(threads * perThread, read.toSet().size)
    }

    @Test fun `non-ASCII text is stored as UTF-8 and read back`() {
        val log = log(cap = 1024)
        log.append(LogLine(1, LogLevel.INFO, LogSource.APP, "café 中文"))
        assertEquals("café 中文", log.readAll().single().message)
    }

    @Test fun `a fresh log has no files until the first line`() {
        val log = log()
        assertFalse(File(tmp.root, "logs").exists())
        log.append(logLine(0))
        assertTrue(File(tmp.root, "logs/${RollingLogFile.CURRENT}").isFile)
    }
}
