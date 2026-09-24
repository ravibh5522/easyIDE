package dev.easyide.app.diagnostics

import dev.easyide.app.data.SandboxImages
import dev.easyide.sandbox.SandboxPaths
import dev.easyide.sandbox.model.SandboxEnvironment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Directories outside [SandboxPaths] that the Storage section measures. They must be disjoint
 * from each other and from the sandbox root, or their bytes would be counted twice.
 *
 * @property logs the directory the [AppLog] writes into.
 * @property sessionBackups where workspace session backups are kept.
 */
data class StorageLocations(val logs: File, val sessionBackups: File)

/**
 * Gathers a [DiagnosticsReport] from the live device: build and kernel, the bundled proot,
 * each environment, disk usage and recent problems. Everything runs on [io]; nothing is
 * cached, so a refresh always reflects the disk as it is now.
 *
 * Hashing an image archive (about 30 MB) is not part of [collect]: it is [verifyChecksum],
 * run per environment when the user asks, so opening the screen stays cheap.
 */
class DiagnosticsCollector(
    private val build: BuildInfo,
    private val uname: () -> Uname,
    private val paths: SandboxPaths,
    private val environments: Flow<List<SandboxEnvironment>>,
    private val nativeLibraryDir: File,
    private val storage: StorageLocations,
    private val appLog: AppLog,
    private val crashReports: CrashReports,
    private val prootProbe: ProotProbe = ProotProbe(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun collect(): DiagnosticsReport = withContext(io) {
        val described = coroutineScope { environments.first().map { async { describe(it) } }.awaitAll() }
        DiagnosticsReport(
            build = build,
            uname = uname(),
            proot = prootProbe.probe(File(nativeLibraryDir, PROOT_LIB)),
            environments = described,
            storage = storageUsage(described),
            recentProblems = readOrDefault(emptyList()) { appLog.errors(RECENT_PROBLEMS_LIMIT) },
            lastCrash = readOrDefault(null) { lastCrash() },
            collectedAtMs = clock(),
        )
    }

    /**
     * Hashes [environment]'s cached image archive against the pinned digest.
     *
     * @throws IOException if the archive cannot be read.
     */
    suspend fun verifyChecksum(environment: EnvironmentDiagnostics): ChecksumStatus = withContext(io) {
        ImageChecksum.verify(pinnedImage(environment.imageId))
    }

    private fun describe(env: SandboxEnvironment): EnvironmentDiagnostics {
        val image = SandboxImages.byId(env.imageId)
        return EnvironmentDiagnostics(
            id = env.id,
            label = env.label,
            backend = env.backend,
            state = env.state,
            failureReason = env.failureReason,
            imageId = image.id,
            imageLabel = image.label,
            rootfsVersion = OsRelease.read(paths.rootfsDir(env.id)),
            checksum = ImageChecksum.quickStatus(pinnedImage(image.id)),
            diskBytes = DiskUsage.bytesOf(paths.environmentDir(env.id)),
        )
    }

    private fun pinnedImage(imageId: String): PinnedImage? =
        ImageChecksum.pinnedFor(SandboxImages.byId(imageId), build.abis, paths)

    private fun storageUsage(described: List<EnvironmentDiagnostics>) = StorageUsage(
        environmentsBytes = described.sumOf { it.diskBytes },
        imageCacheBytes = DiskUsage.bytesOf(paths.imageCacheDir),
        projectsBytes = DiskUsage.bytesOf(paths.projectsDir),
        extensionsBytes = DiskUsage.bytesOf(paths.extensionsDir),
        logsBytes = DiskUsage.bytesOf(storage.logs) + DiskUsage.bytesOf(crashReports.dir),
        sessionBackupsBytes = DiskUsage.bytesOf(storage.sessionBackups),
    )

    private fun lastCrash(): CrashSummary? {
        val ref = crashReports.latestSeenOrPending() ?: return null
        return CrashSummary(ref.atMs, CrashReportFormat.headline(crashReports.read(ref)), pending = !ref.seen)
    }

    // The log and crash files are read from disk, so a damaged one is an expected input: the
    // rest of the report is still worth showing.
    private fun <T> readOrDefault(default: T, read: () -> T): T =
        try {
            read()
        } catch (unreadable: IOException) {
            default
        }

    companion object {
        const val RECENT_PROBLEMS_LIMIT = 20

        /**
         * The bundled proot's file name in `nativeLibraryDir`. jniLibs entries must be named
         * `lib*.so` to be packaged; the sandbox runtime keeps this constant private.
         */
        const val PROOT_LIB = "libproot.so"
    }
}
