package dev.easyide.app.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One stored crash report. [atMs] is also its identity: files are named `crash-<atMs>.txt`. */
data class CrashReportRef(val atMs: Long, val seen: Boolean) {
    val fileName: String get() = "$PREFIX$atMs$SUFFIX"

    internal companion object {
        const val PREFIX = "crash-"
        const val SUFFIX = ".txt"
    }
}

/**
 * The crash reports on disk, at most [maxKept] of them, newest kept.
 *
 * A report is *pending* until the user has been told about it (the recovery dialog on the next
 * launch); [acknowledge] then moves it to [seenDir] rather than deleting it, so the
 * Diagnostics screen can still list and export it. Reports are small text files written by the
 * crash handler, so a plain directory needs no index.
 *
 * All methods take one lock: the crash handler can run on any thread while the UI reads.
 */
class CrashReports(val dir: File, private val maxKept: Int = DEFAULT_MAX_KEPT) {
    private val lock = Any()

    /** Acknowledged reports; [Cleanup] clears it and the Diagnostics screen still lists it. */
    val seenDir: File = File(dir, SEEN)

    /** Stores [report] as a new pending report and drops the oldest beyond [maxKept]. */
    fun write(report: String, atMs: Long): CrashReportRef = synchronized(lock) {
        Files.createDirectories(dir.toPath())
        // Two threads can die in the same millisecond; the id must stay unique.
        var id = atMs
        while (locate(CrashReportRef(id, seen = false)) != null || locate(CrashReportRef(id, seen = true)) != null) id += 1
        val ref = CrashReportRef(id, seen = false)
        File(dir, ref.fileName).writeText(report, Charsets.UTF_8)
        list().drop(maxKept).forEach { Files.deleteIfExists(fileOf(it).toPath()) }
        ref
    }

    /** Pending and seen reports, newest first. */
    fun list(): List<CrashReportRef> = synchronized(lock) {
        (refsIn(dir, seen = false) + refsIn(seenDir, seen = true)).sortedByDescending { it.atMs }
    }

    /** The newest report the user has not been told about yet. */
    fun pending(): CrashReportRef? = synchronized(lock) { refsIn(dir, seen = false).maxByOrNull { it.atMs } }

    /**
     * Marks [ref] handled. Every pending report not newer than it is moved too: the one dialog
     * that acknowledges the newest crash stands for the older ones, so they do not each
     * re-open it on later launches.
     */
    fun acknowledge(ref: CrashReportRef) {
        synchronized(lock) {
            val moving = refsIn(dir, seen = false).filter { it.atMs <= ref.atMs }
            if (moving.isEmpty()) return
            Files.createDirectories(seenDir.toPath())
            for (r in moving) {
                Files.move(File(dir, r.fileName).toPath(), File(seenDir, r.fileName).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    fun latestSeenOrPending(): CrashReportRef? = list().firstOrNull()

    /** @throws IOException if the report was deleted meanwhile. */
    fun read(ref: CrashReportRef): String = synchronized(lock) {
        val file = locate(ref) ?: throw IOException("Crash report ${ref.fileName} is gone")
        file.readText(Charsets.UTF_8)
    }

    /** The report's location whichever directory it is in now, or null when it does not exist. */
    private fun locate(ref: CrashReportRef): File? =
        listOf(File(dir, ref.fileName), File(seenDir, ref.fileName)).firstOrNull { it.isFile }

    private fun fileOf(ref: CrashReportRef): File = File(if (ref.seen) seenDir else dir, ref.fileName)

    private fun refsIn(directory: File, seen: Boolean): List<CrashReportRef> =
        directory.listFiles().orEmpty().mapNotNull { file ->
            val name = file.name
            if (!file.isFile || !name.startsWith(CrashReportRef.PREFIX) || !name.endsWith(CrashReportRef.SUFFIX)) return@mapNotNull null
            name.removePrefix(CrashReportRef.PREFIX).removeSuffix(CrashReportRef.SUFFIX).toLongOrNull()
                ?.let { CrashReportRef(it, seen) }
        }

    companion object {
        const val DEFAULT_MAX_KEPT = 5
        const val SEEN = "seen"
    }
}
