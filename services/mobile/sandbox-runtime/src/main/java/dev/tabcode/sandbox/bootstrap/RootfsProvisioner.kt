package dev.tabcode.sandbox.bootstrap

import dev.tabcode.sandbox.SandboxError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Progress callback so the caller can stream status into the terminal. */
typealias ProgressReporter = (String) -> Unit

/**
 * Downloads a distro rootfs tarball and unpacks it into an environment.
 *
 * Extraction uses the in-repo [TarGzExtractor], not the platform `tar`:
 * Android denies `link(2)` in app-private storage, so `tar` aborts partway
 * through a real rootfs ("can't link 'usr/bin/perl5.38.2' -> 'usr/bin/perl':
 * Permission denied"). The in-repo extractor copies hard links instead.
 */
class RootfsProvisioner(
    private val ioDispatcher: CoroutineDispatcher,
    private val extractor: TarGzExtractor = TarGzExtractor(),
) {

    /**
     * @param archive device-level cache location for the image. Shared across
     *   environments, so a second environment from the same image extracts
     *   straight from disk with no download.
     */
    suspend fun provision(
        tarballUrl: String,
        rootfs: File,
        archive: File,
        onProgress: ProgressReporter,
    ): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            rootfs.mkdirs()
            archive.parentFile?.mkdirs()

            if (archive.isFile && archive.length() > 0) {
                onProgress("Using cached image (${archive.length() / MB} MB) - no download needed")
            } else {
                download(tarballUrl, archive, onProgress)
            }

            onProgress("Extracting rootfs (this takes a minute)...")
            val result = archive.inputStream().buffered().use { extractor.extract(it, rootfs) }
            onProgress(
                "Extracted ${result.entries} files, ${result.symlinks} links, " +
                    "${result.hardLinksCopied} hard links copied"
            )

            ensureGuestDefaults(rootfs)
            onProgress("Rootfs ready at ${rootfs.name}")
        }
    }

    /**
     * Applies the small set of fixes a freshly-unpacked distro needs. Called on
     * every launch, not just at provision time, so environments created before
     * a fix existed pick it up instead of staying subtly broken.
     */
    fun ensureGuestDefaults(rootfs: File) {
        runCatching { configureDns(rootfs) }
        runCatching { installSudoShim(rootfs) }
        runCatching { removePaxHeaderArtifacts(rootfs) }
    }

    /**
     * Deletes the `PaxHeaders` entries an earlier [TarGzExtractor] wrote when it
     * mistook pax extended headers for files. dpkg reads every file in
     * `/etc/dpkg/dpkg.cfg.d` and dies on the directory it finds there
     * ("read error in configuration file ...: Is a directory"), which broke
     * `apt-get install` in every environment created by those builds.
     *
     * Repairing in place rather than forcing a reinstall keeps the packages the
     * user already installed. The marker means the tree is walked once, not on
     * every launch.
     */
    private fun removePaxHeaderArtifacts(rootfs: File) {
        val marker = File(rootfs.parentFile ?: rootfs, PAX_REPAIR_MARKER)
        if (marker.exists()) return

        // Collected before deleting: the walk would otherwise be descending
        // into directories that are disappearing underneath it.
        rootfs.walkTopDown()
            .filter { it.name.startsWith(PAX_HEADER_NAME) }
            .toList()
            .forEach { it.deleteRecursively() }

        marker.writeText(PAX_REPAIR_NOTE)
    }

    private fun download(url: String, destination: File, onProgress: ProgressReporter) {
        onProgress("Downloading $url")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }

        try {
            if (connection.responseCode !in HTTP_OK_RANGE) {
                throw SandboxError.ProvisioningFailed(
                    destination.name,
                    "download failed with HTTP ${connection.responseCode}",
                )
            }
            val total = connection.contentLengthLong
            val partial = File(destination.parentFile, destination.name + PARTIAL_SUFFIX)

            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER)
                    var copied = 0L
                    var lastReportedMb = -1L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        // Report per megabyte, not per chunk, so the terminal
                        // does not drown in progress lines.
                        val mb = copied / MB
                        if (mb != lastReportedMb) {
                            lastReportedMb = mb
                            onProgress(progressLine(mb, total))
                        }
                    }
                }
            }
            // Rename only after a complete download, so an interrupted one is
            // never mistaken for a usable cached archive.
            if (!partial.renameTo(destination)) {
                throw SandboxError.StorageFailure("finalize ${destination.name}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun progressLine(mb: Long, total: Long): String =
        if (total > 0) "Downloaded $mb / ${total / MB} MB" else "Downloaded $mb MB"

    /** ubuntu-base ships an empty resolv.conf, so DNS fails until this is written. */
    private fun configureDns(rootfs: File) {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        File(etc, "resolv.conf").writeText(DEFAULT_RESOLV_CONF)
    }

    /**
     * `ubuntu-base` has no `sudo`, and under proot the shell is already uid 0,
     * so real sudo would be pointless anyway - it would also fail, because it
     * is setuid and proot cannot honour that.
     *
     * A pass-through shim makes the muscle-memory `sudo apt install ...` work
     * instead of failing with "command not found". Flags that take a value are
     * consumed so `sudo -u root foo` does not try to execute "root".
     */
    private fun installSudoShim(rootfs: File) {
        // Never shadow a real sudo the user installed themselves.
        if (File(rootfs, "usr/bin/sudo").exists()) return

        val binDir = File(rootfs, "usr/local/bin").apply { mkdirs() }
        val sudo = File(binDir, "sudo")
        if (sudo.isFile && sudo.readText() == SUDO_SHIM) return
        sudo.writeText(SUDO_SHIM)
        sudo.setExecutable(true, false)
    }

    private companion object {
        const val ARCHIVE_NAME = "rootfs.tar.gz"
        const val PARTIAL_SUFFIX = ".part"
        const val TIMEOUT_MS = 30_000
        const val DOWNLOAD_BUFFER = 64 * 1024
        const val MB = 1024L * 1024
        val HTTP_OK_RANGE = 200..299

        /** Covers both `PaxHeaders` and the older `PaxHeaders.<pid>` naming. */
        const val PAX_HEADER_NAME = "PaxHeaders"
        const val PAX_REPAIR_MARKER = ".pax-repair-done"
        const val PAX_REPAIR_NOTE = "PaxHeaders artifacts removed from this rootfs.\n"

        val DEFAULT_RESOLV_CONF = """
            nameserver 8.8.8.8
            nameserver 1.1.1.1
        """.trimIndent() + "\n"

        val SUDO_SHIM = """
            #!/bin/sh
            # proot already runs everything as uid 0, so sudo is a pass-through.
            while [ ${'$'}# -gt 0 ]; do
              case "${'$'}1" in
                -u|-g|-p|-C|-h|-r|-t|-U) shift 2 ;;
                -*) shift ;;
                *) break ;;
              esac
            done
            if [ ${'$'}# -eq 0 ]; then
              echo "sudo: already running as root in this sandbox" >&2
              exit 0
            fi
            exec "${'$'}@"
        """.trimIndent() + "\n"
    }
}
