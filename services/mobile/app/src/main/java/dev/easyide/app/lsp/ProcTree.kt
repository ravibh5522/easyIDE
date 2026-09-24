package dev.easyide.app.lsp

import java.io.File
import java.io.IOException

/**
 * One process as `/proc` describes it. [startTicks] (field 22 of `stat`, clock ticks after
 * boot) tells a reused pid from the process that held it before.
 */
data class ProcEntry(val pid: Int, val ppid: Int, val startTicks: Long, val cmdline: List<String>)

/**
 * Identifies a language server's host process: the proot launcher the app forked, whose argv
 * ends with the guest command and binds this project's directory. Android's `Process` exposes
 * no pid at any API level, so the only way to find it is to look for a child of the app
 * ([parentPid]) whose command line says what we launched.
 *
 * @property argvTail the guest command exactly as the launcher appended it to the argv.
 * @property bindToken the `-b host:guest` value of the project bind; it separates the same
 *   server running for two projects.
 */
data class ProcMatch(val parentPid: Int, val argvTail: List<String>, val bindToken: String) {
    fun matches(entry: ProcEntry): Boolean {
        val cmd = entry.cmdline
        if (entry.ppid != parentPid || cmd.size < argvTail.size) return false
        return cmd.subList(cmd.size - argvTail.size, cmd.size) == argvTail && bindToken in cmd
    }
}

/**
 * Read-only view of a procfs mount. [root] is `/proc` on the device and a fixture directory
 * in tests; every read is a boundary (processes vanish between listing and reading), so an
 * unreadable entry is skipped, never an error.
 */
class ProcTree(private val root: File = File(PROC_ROOT)) {

    /** Every readable process. Other apps' processes are hidden from us on Android; ours are not. */
    fun entries(): List<ProcEntry> {
        val dirs = root.listFiles() ?: return emptyList()
        return dirs.mapNotNull { dir -> dir.name.toIntOrNull()?.let(::entry) }
    }

    fun entry(pid: Int): ProcEntry? {
        val dir = File(root, pid.toString())
        val stat = read(File(dir, STAT))?.let(::parseStat) ?: return null
        val cmdline = readBytes(File(dir, CMDLINE))?.let(::parseCmdline) ?: return null
        return ProcEntry(pid, stat.ppid, stat.startTicks, cmdline)
    }

    /** The newest process [match] accepts; a restart leaves the old one exiting for a moment. */
    fun find(match: ProcMatch): ProcEntry? = entries().filter(match::matches).maxByOrNull { it.startTicks }

    /**
     * Resident size of [rootPid] and every descendant in KiB, or null when the root itself is
     * gone. A server may fork workers (node, pylsp plugins); they count against its budget.
     */
    fun treeRssKb(rootPid: Int, all: List<ProcEntry> = entries()): Long? {
        val own = rssKb(rootPid) ?: return null
        return own + descendants(rootPid, all).sumOf { rssKb(it) ?: 0L }
    }

    fun rssKb(pid: Int): Long? = read(File(File(root, pid.toString()), STATUS))?.let(::parseVmRssKb)

    private fun read(file: File): String? = readBytes(file)?.decodeToString()

    private fun readBytes(file: File): ByteArray? = try {
        file.readBytes()
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    data class Stat(val ppid: Int, val startTicks: Long)

    companion object {
        const val PROC_ROOT = "/proc"
        private const val STAT = "stat"
        private const val CMDLINE = "cmdline"
        private const val STATUS = "status"
        private const val VM_RSS = "VmRSS:"

        /** `stat` fields after the `(comm)`: state is field 3, ppid 4, starttime 22 (1-based). */
        private const val PPID_AFTER_COMM = 1
        private const val START_TIME_AFTER_COMM = 19

        /**
         * Parses `/proc/<pid>/stat`. `comm` may itself contain spaces and parentheses, so
         * fields are counted from the last `)`, the only unambiguous delimiter.
         */
        fun parseStat(text: String): Stat? {
            val close = text.lastIndexOf(')')
            if (close < 0) return null
            val fields = text.substring(close + 1).trim().split(' ')
            val ppid = fields.getOrNull(PPID_AFTER_COMM)?.toIntOrNull() ?: return null
            val start = fields.getOrNull(START_TIME_AFTER_COMM)?.toLongOrNull() ?: return null
            return Stat(ppid, start)
        }

        /** NUL-separated argv; a kernel thread or zombie has an empty one. */
        fun parseCmdline(bytes: ByteArray): List<String> {
            if (bytes.isEmpty()) return emptyList()
            val text = bytes.decodeToString().removeSuffix("\u0000")
            return text.split('\u0000')
        }

        /** `VmRSS:     12345 kB`, or null when absent (kernel threads, zombies). */
        fun parseVmRssKb(status: String): Long? =
            status.lineSequence().firstOrNull { it.startsWith(VM_RSS) }
                ?.removePrefix(VM_RSS)?.trim()?.substringBefore(' ')?.toLongOrNull()

        /** Pids below [rootPid] in the ppid forest of [all]; cycles (pid reuse) cannot loop. */
        fun descendants(rootPid: Int, all: List<ProcEntry>): List<Int> {
            val children = all.groupBy({ it.ppid }, { it.pid })
            val out = ArrayList<Int>()
            val seen = hashSetOf(rootPid)
            val queue = ArrayDeque(children[rootPid].orEmpty())
            while (queue.isNotEmpty()) {
                val pid = queue.removeFirst()
                if (!seen.add(pid)) continue
                out += pid
                queue.addAll(children[pid].orEmpty())
            }
            return out
        }
    }
}
