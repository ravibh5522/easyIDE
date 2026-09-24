package dev.easyide.app.diagnostics

import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.SandboxBackend

/** One environment as the Sandbox section shows it. [diskBytes] covers its rootfs, installs and extensions. */
data class EnvironmentDiagnostics(
    val id: String,
    val label: String,
    val backend: SandboxBackend,
    val state: EnvironmentState,
    val failureReason: String?,
    val imageId: String,
    val imageLabel: String,
    /** From the rootfs's os-release; null when unreadable or not provisioned. */
    val rootfsVersion: String?,
    val checksum: ChecksumStatus,
    val diskBytes: Long,
)

/**
 * Bytes per storage area. The areas are disjoint directories, so [total] is their plain sum:
 * environments (each with its own installs), image cache, projects, global extensions, logs
 * with crash reports, and session backups.
 */
data class StorageUsage(
    val environmentsBytes: Long,
    val imageCacheBytes: Long,
    val projectsBytes: Long,
    val extensionsBytes: Long,
    val logsBytes: Long,
    val sessionBackupsBytes: Long,
) {
    val total: Long
        get() = environmentsBytes + imageCacheBytes + projectsBytes + extensionsBytes + logsBytes + sessionBackupsBytes
}

/** The newest crash report on disk: [headline] is its exception line; [pending] means the user was not told yet. */
data class CrashSummary(val atMs: Long, val headline: String, val pending: Boolean)

/** Everything the Diagnostics screen shows, gathered once by [DiagnosticsCollector]. */
data class DiagnosticsReport(
    val build: BuildInfo,
    val uname: Uname,
    val proot: ProotStatus,
    val environments: List<EnvironmentDiagnostics>,
    val storage: StorageUsage,
    val recentProblems: List<LogLine>,
    val lastCrash: CrashSummary?,
    val collectedAtMs: Long,
)
