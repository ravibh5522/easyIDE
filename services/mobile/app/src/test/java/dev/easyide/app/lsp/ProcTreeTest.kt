package dev.easyide.app.lsp

import dev.easyide.sandbox.shell.ServerProcessFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ProcTreeTest {

    private val root = File(requireNotNull(javaClass.classLoader?.getResource("proc")).toURI())
    private val proc = ProcTree(root)
    private val guest = ServerProcessFactory.guestArgv(listOf("pyright-langserver", "--stdio"))
    private val match = ProcMatch(parentPid = 100, argvTail = guest, bindToken = "/data/app/files/projects/p1:/workspace")

    @Test
    fun statParsesFromTheLastParenthesis() {
        val stat = ProcTree.parseStat("201 (node (worker) )) S 200 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1001 0\n")
        assertEquals(200, stat?.ppid)
        assertEquals(1001L, stat?.startTicks)
        assertNull(ProcTree.parseStat("garbage"))
    }

    @Test
    fun cmdlineAndRssParse() {
        assertEquals(listOf("a", "b c", ""), ProcTree.parseCmdline("a\u0000b c\u0000\u0000".toByteArray()))
        assertEquals(emptyList<String>(), ProcTree.parseCmdline(ByteArray(0)))
        assertEquals(123L, ProcTree.parseVmRssKb("Name:\tx\nVmRSS:\t   123 kB\n"))
        assertNull(ProcTree.parseVmRssKb("Name:\tx\n"))
    }

    @Test
    fun entriesSkipNonProcessFiles() {
        val pids = proc.entries().map { it.pid }.toSet()
        assertEquals(setOf(100, 200, 201, 202, 203, 210, 300, 400), pids)
    }

    @Test
    fun findPicksTheNewestProotOfThisProjectAndApp() {
        // 210 is an older start (a restart's predecessor), 300 another project, 400 not our child.
        assertEquals(200, proc.find(match)?.pid)
    }

    @Test
    fun treeRssSumsTheProcessAndEveryDescendant() {
        // proot 4000 + node 150000 + worker 30000; the zombie has no VmRSS and counts 0.
        assertEquals(184_000L, proc.treeRssKb(200))
        assertNull(proc.treeRssKb(999))
    }

    @Test
    fun descendantsSurviveCycles() {
        val cyclic = listOf(ProcEntry(1, 2, 0, emptyList()), ProcEntry(2, 1, 0, emptyList()))
        assertEquals(listOf(2), ProcTree.descendants(1, cyclic))
    }
}
