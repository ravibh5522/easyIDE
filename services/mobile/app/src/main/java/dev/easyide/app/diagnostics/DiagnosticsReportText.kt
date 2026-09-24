package dev.easyide.app.diagnostics

/**
 * The report as plain text, for the head of an exported log and for the share sheet. It is a
 * technical artifact meant for a maintainer's eyes (like the crash report), so it is English
 * and fixed-format rather than localized UI copy.
 */
object DiagnosticsReportText {
    /** The two lines that identify the build and device; all a share needs before a full report exists. */
    fun header(b: BuildInfo): String =
        "App: ${b.versionName} (code ${b.versionCode}), build ${b.channel}\n" +
            "Device: ${b.manufacturer} ${b.model}, Android SDK ${b.sdkInt}, ABIs ${b.abis.joinToString(", ")}\n"

    fun render(report: DiagnosticsReport): String = buildString {
        appendLine("easyIDE diagnostics")
        appendLine("Collected: ${LogFormat.formatTime(report.collectedAtMs)}")
        append(header(report.build))
        appendLine("Kernel: ${report.uname.sysname} ${report.uname.release} ${report.uname.machine}")
        appendLine("proot: ${proot(report.proot)}")
        for (env in report.environments) {
            appendLine("Environment ${env.label} (${env.id}): ${env.backend}, ${env.state}${env.failureReason?.let { " ($it)" }.orEmpty()}")
            appendLine("  image ${env.imageLabel} (${env.imageId}), rootfs ${env.rootfsVersion ?: "unknown"}, checksum ${env.checksum}, ${env.diskBytes} bytes")
        }
        val s = report.storage
        appendLine("Storage bytes: environments ${s.environmentsBytes}, images ${s.imageCacheBytes}, projects ${s.projectsBytes}, " +
            "extensions ${s.extensionsBytes}, logs ${s.logsBytes}, session backups ${s.sessionBackupsBytes}, total ${s.total}")
        report.lastCrash?.let { appendLine("Last crash: ${LogFormat.formatTime(it.atMs)} ${it.headline}${if (it.pending) " (not yet seen)" else ""}") }
        if (report.recentProblems.isNotEmpty()) {
            appendLine("Recent problems:")
            report.recentProblems.forEach { appendLine("  ${LogFormat.encode(it)}") }
        }
    }

    private fun proot(status: ProotStatus): String = when (status) {
        is ProotStatus.Found -> "${status.version} (${status.path})"
        is ProotStatus.Unavailable -> "unavailable: ${status.failure}${status.detail?.let { " ($it)" }.orEmpty()} (${status.path})"
    }
}
