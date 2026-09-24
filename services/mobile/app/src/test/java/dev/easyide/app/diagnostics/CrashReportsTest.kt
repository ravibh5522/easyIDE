package dev.easyide.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CrashReportsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dir get() = File(tmp.root, "crashes")

    @Test fun `nothing is pending in a fresh directory`() {
        val reports = CrashReports(dir)
        assertNull(reports.pending())
        assertNull(reports.latestSeenOrPending())
        assertEquals(emptyList<CrashReportRef>(), reports.list())
    }

    @Test fun `a written report is pending and readable, named by its time`() {
        val reports = CrashReports(dir)
        val ref = reports.write("report text", atMs = 1234)
        assertEquals(CrashReportRef(1234, seen = false), ref)
        assertEquals("crash-1234.txt", ref.fileName)
        assertTrue(File(dir, "crash-1234.txt").isFile)
        assertEquals(ref, reports.pending())
        assertEquals("report text", reports.read(ref))
    }

    @Test fun `pending is the newest unacknowledged report`() {
        val reports = CrashReports(dir)
        reports.write("old", 100)
        val newest = reports.write("new", 300)
        reports.write("middle", 200)
        assertEquals(newest, reports.pending())
        assertEquals(listOf(300L, 200L, 100L), reports.list().map { it.atMs })
    }

    @Test fun `acknowledging moves the report to seen and it is still listed`() {
        val reports = CrashReports(dir)
        val ref = reports.write("text", 500)
        reports.acknowledge(ref)
        assertNull(reports.pending())
        assertTrue(File(dir, "seen/crash-500.txt").isFile)
        assertFalse(File(dir, "crash-500.txt").exists())
        assertEquals(listOf(CrashReportRef(500, seen = true)), reports.list())
        assertEquals(CrashReportRef(500, seen = true), reports.latestSeenOrPending())
        assertEquals("text", reports.read(ref))
    }

    @Test fun `acknowledging the newest also acknowledges older pending reports but not newer ones`() {
        val reports = CrashReports(dir)
        reports.write("a", 100)
        val b = reports.write("b", 200)
        val c = reports.write("c", 300)
        reports.acknowledge(b)
        assertEquals(c, reports.pending())
        assertEquals(listOf(true, true, false), reports.list().reversed().map { it.seen })
    }

    @Test fun `acknowledging twice is harmless`() {
        val reports = CrashReports(dir)
        val ref = reports.write("x", 1)
        reports.acknowledge(ref)
        reports.acknowledge(ref)
        assertNull(reports.pending())
        assertEquals(1, reports.list().size)
    }

    @Test fun `only the newest maxKept reports are kept, seen or pending`() {
        val reports = CrashReports(dir, maxKept = 3)
        (1..5).forEach {
            val ref = reports.write("r$it", it * 100L)
            if (it == 2) reports.acknowledge(ref)
        }
        assertEquals(listOf(500L, 400L, 300L), reports.list().map { it.atMs })
        assertEquals(0, File(dir, "seen").listFiles().orEmpty().size)
    }

    @Test fun `two crashes in the same millisecond do not overwrite each other`() {
        val reports = CrashReports(dir)
        val first = reports.write("first", 700)
        val second = reports.write("second", 700)
        assertEquals(700L, first.atMs)
        assertEquals(701L, second.atMs)
        assertEquals("first", reports.read(first))
        assertEquals("second", reports.read(second))
    }

    @Test fun `unrelated files in the directory are ignored`() {
        val reports = CrashReports(dir)
        dir.mkdirs()
        File(dir, "notes.txt").writeText("hi")
        File(dir, "crash-abc.txt").writeText("bad id")
        File(dir, "crash-5.txt.tmp").writeText("partial")
        assertEquals(emptyList<CrashReportRef>(), reports.list())
    }

    @Test fun `reading a deleted report fails with an IOException`() {
        val reports = CrashReports(dir)
        val ref = reports.write("gone soon", 9)
        File(dir, ref.fileName).delete()
        try {
            reports.read(ref)
            org.junit.Assert.fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("crash-9.txt"))
        }
    }
}
