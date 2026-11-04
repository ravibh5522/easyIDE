package dev.easyide.sandbox.bootstrap

import java.io.File

/**
 * Real progress of a preset's setup command, read from the machine-readable lines the command itself
 * prints. apt reports them when `APT::Status-Fd` is set (see [configFile]); `easyide-install-node`
 * prints `easyide-progress:<percent>` at its phase boundaries when `EASYIDE_PROGRESS` is set. Both are
 * what the tools say about themselves - nothing here estimates from elapsed time.
 *
 * apt's `dlstatus` percent covers the downloads and `pmstatus` the dpkg unpack and configure of the
 * whole transaction; dpkg is by far the slower part under proot, so it gets most of the step.
 */
internal object SetupProgress {

    /** A parsed status line: how far through the command ([fraction], 0..1) and what it says it is doing. */
    data class Update(val fraction: Float, val message: String?)

    const val CONFIG_GUEST_PATH = "/var/tmp/easyide-setup-apt.conf"
    val ENVIRONMENT = mapOf("APT_CONFIG" to CONFIG_GUEST_PATH, "EASYIDE_PROGRESS" to "1")

    /**
     * Status lines on stdout (where the rest of the output goes, so the order is kept), no translation
     * indexes to fetch, and dpkg without per-file fsync: the rootfs is disposable until the install
     * finishes, and every fsync under proot is a round trip.
     */
    private const val CONFIG = """
APT::Status-Fd "1";
Acquire::Languages "none";
Dpkg::Options { "--force-unsafe-io"; };
"""

    fun configFile(rootfs: File): File = File(rootfs, CONFIG_GUEST_PATH.removePrefix("/"))

    fun writeConfig(rootfs: File) {
        val file = configFile(rootfs)
        file.parentFile?.mkdirs()
        file.writeText(CONFIG.trimStart())
    }

    private const val DOWNLOAD_SHARE = 0.25f

    /** Null for an ordinary output line. */
    fun parse(line: String): Update? {
        if (line.startsWith(MARKER)) return line.removePrefix(MARKER).toFloatOrNull()?.let { Update(percent(it), null) }
        val parts = line.split(':', limit = 4)
        if (parts.size < 4) return null
        val percent = parts[2].toFloatOrNull() ?: return null
        return when (parts[0]) {
            "dlstatus" -> Update(DOWNLOAD_SHARE * percent(percent), null)
            "pmstatus" -> Update(DOWNLOAD_SHARE + (1f - DOWNLOAD_SHARE) * percent(percent), parts[3].trim().ifEmpty { null })
            else -> null
        }
    }

    private fun percent(value: Float) = (value / 100f).coerceIn(0f, 1f)

    private const val MARKER = "easyide-progress:"
}
