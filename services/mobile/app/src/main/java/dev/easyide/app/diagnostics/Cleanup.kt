package dev.easyide.app.diagnostics

import dev.easyide.sandbox.SandboxPaths
import java.io.File
import java.io.IOException

/**
 * The three things the Storage section can clear, and nothing else: it never touches
 * projects, session backups, extensions, or any rootfs path outside the apt and temp
 * directories named below. Every deletion goes through [SafeTree], so a symlink inside a
 * rootfs cannot redirect it to other data.
 */
class Cleanup(
    private val paths: SandboxPaths,
    private val appLog: AppLog,
    private val crashReports: CrashReports,
) {
    sealed interface Target {
        /** Downloaded `.deb` files, apt package lists and `/tmp` of one environment. All are rebuilt on demand. */
        data class EnvironmentPackageCache(val environmentId: String) : Target

        /** The downloaded rootfs archives: only needed to create new environments, re-downloaded when it happens. */
        data object ImageArchives : Target

        /** The app log and the crash reports the user has already been told about. */
        data object Logs : Target
    }

    /** What a cleanup did: bytes actually freed and entries that could not be removed. */
    data class Result(val freedBytes: Long, val failedEntries: Int)

    /**
     * Bytes [run] would free, for the confirmation dialog.
     *
     * @throws IOException if a target resolves outside its base ([RefusedDeletion]) or cannot be read.
     */
    fun estimate(target: Target): Long = when (target) {
        is Target.EnvironmentPackageCache -> regionBytes(environmentRegions(target.environmentId))
        Target.ImageArchives -> regionBytes(listOf(imageRegion()))
        Target.Logs -> appLog.sizeBytes() + SafeTree.contentsBytes(crashReports.seenDir, crashReports.dir)
    }

    /** @throws IOException as [estimate]; a failure part-way leaves earlier regions already cleared. */
    fun run(target: Target): Result = when (target) {
        is Target.EnvironmentPackageCache -> deleteRegions(environmentRegions(target.environmentId))
        Target.ImageArchives -> deleteRegions(listOf(imageRegion()))
        Target.Logs -> {
            val logBytes = appLog.sizeBytes()
            appLog.clear()
            val crashes = SafeTree.deleteContents(crashReports.seenDir, crashReports.dir)
            Result(logBytes + crashes.bytes, crashes.failedEntries)
        }
    }

    /** One directory whose contents may be deleted, and the base it must stay inside. */
    private class Region(val dir: File, val base: File, val keep: (String) -> Boolean = { false })

    private fun regionBytes(regions: List<Region>): Long =
        regions.sumOf { SafeTree.contentsBytes(it.dir, it.base, it.keep) }

    private fun deleteRegions(regions: List<Region>): Result {
        val deleted = regions.map { SafeTree.deleteContents(it.dir, it.base, it.keep) }
        return Result(deleted.sumOf { it.bytes }, deleted.sumOf { it.failedEntries })
    }

    private fun environmentRegions(environmentId: String): List<Region> {
        val rootfs = paths.rootfsDir(environmentId)
        return listOf(
            // apt holds `lock` while it runs and expects `partial/` to exist; the .debs go.
            Region(File(rootfs, APT_ARCHIVES), rootfs) { it == APT_LOCK || it == APT_PARTIAL },
            Region(File(rootfs, "$APT_ARCHIVES/$APT_PARTIAL"), rootfs),
            Region(File(rootfs, APT_LISTS), rootfs),
            // A running tmux server keeps its socket in /tmp/tmux-<uid>; deleting it orphans live sessions.
            Region(File(rootfs, TMP), rootfs) { it.startsWith(TMUX_SOCKET_PREFIX) },
        )
    }

    /** Based at the sandbox root, so an image cache directory replaced by a link elsewhere is refused. */
    private fun imageRegion(): Region =
        Region(paths.imageCacheDir, requireNotNull(paths.imageCacheDir.parentFile) { "image cache has no parent" })

    private companion object {
        const val APT_ARCHIVES = "var/cache/apt/archives"
        const val APT_LISTS = "var/lib/apt/lists"
        const val APT_LOCK = "lock"
        const val APT_PARTIAL = "partial"
        const val TMP = "tmp"
        const val TMUX_SOCKET_PREFIX = "tmux-"
    }
}
