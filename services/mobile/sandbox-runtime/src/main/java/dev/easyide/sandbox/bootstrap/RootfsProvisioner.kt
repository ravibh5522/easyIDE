package dev.easyide.sandbox.bootstrap

import dev.easyide.sandbox.SandboxError
import dev.easyide.sandbox.backend.GuestEnvironment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import dev.easyide.sandbox.download.DownloadError
import dev.easyide.sandbox.download.DownloadEvent
import dev.easyide.sandbox.download.DownloadRequest
import dev.easyide.sandbox.download.VerifiedDownloader
import dev.easyide.sandbox.files.SafeTree
import dev.easyide.sandbox.model.RootfsArchive
import java.io.File

/** Progress callback so the caller can stream status into the terminal. */
typealias ProgressReporter = (String) -> Unit

/**
 * Downloads a distro rootfs tarball and unpacks it into an environment.
 *
 * The download goes through [VerifiedDownloader], pinned to the catalog's
 * sha256: nothing is extracted from bytes that do not match it.
 *
 * Extraction uses the in-repo [TarGzExtractor], not the platform `tar`:
 * Android denies `link(2)` in app-private storage, so `tar` aborts partway
 * through a real rootfs ("can't link 'usr/bin/perl5.38.2' -> 'usr/bin/perl':
 * Permission denied"). The in-repo extractor copies hard links instead.
 */
class RootfsProvisioner(
    private val ioDispatcher: CoroutineDispatcher,
    private val extractor: TarGzExtractor = TarGzExtractor(),
    private val downloader: VerifiedDownloader = VerifiedDownloader(ioDispatcher),
) {

    /**
     * @param archive device-level cache location for the image. Shared across
     *   environments, so a second environment from the same image extracts
     *   straight from disk with no download.
     * @return failure with [SandboxError.ProvisioningFailed] when the download
     *   fails or its sha256 does not match [RootfsArchive.sha256]; a mismatch
     *   deletes the partial file, an interruption keeps it for resume.
     * @param onEvent structured progress for a UI; [onProgress] carries the same
     *   story as text. Cancelling the caller stops the download (its partial
     *   file is kept for resume) or the extraction (its half-unpacked rootfs is
     *   removed, so it can never be mistaken for a usable one).
     */
    suspend fun provision(
        source: RootfsArchive,
        rootfs: File,
        archive: File,
        onProgress: ProgressReporter,
        onEvent: (InstallEvent) -> Unit = {},
    ): Result<Unit> = runCatching {
        withContext(ioDispatcher) {
            rootfs.mkdirs()
            // A cached archive is re-hashed, not trusted: images cached by
            // builds that predate checksums, or damaged on disk, are replaced
            // instead of being extracted into a broken rootfs.
            fetch(source, archive, onProgress, onEvent)

            onProgress("Extracting rootfs (this takes a minute)...")
            val result = extractOrWipe(archive, rootfs, onEvent)
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
    suspend fun ensureGuestDefaults(rootfs: File) {
        withContext(ioDispatcher) {
            runCatching { configureDns(rootfs) }
            runCatching { installSudoShim(rootfs) }
            runCatching { removePaxHeaderArtifacts(rootfs) }
            runCatching { createDefaultUser(rootfs) }
        }
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

    /**
     * Fetches (or re-verifies the cached copy of) [source] into [archive].
     * Progress is reported per megabyte, not per event, so the terminal does
     * not drown in progress lines.
     */
    private suspend fun fetch(
        source: RootfsArchive,
        archive: File,
        onProgress: ProgressReporter,
        onEvent: (InstallEvent) -> Unit,
    ) {
        var resumedFrom = 0L
        var lastReportedMb = -1L
        val request = DownloadRequest(
            url = source.url,
            sha256 = source.sha256,
            maxBytes = MAX_ROOTFS_BYTES,
            destination = archive,
        )
        try {
            downloader.download(request).collect { event ->
                when (event) {
                    is DownloadEvent.Started -> {
                        resumedFrom = event.resumedFrom
                        onEvent(InstallEvent.Downloading(event.resumedFrom, event.totalBytes, event.resumedFrom))
                        onProgress(
                            if (event.resumedFrom > 0) {
                                "Resuming ${source.url} at ${event.resumedFrom / MB} MB"
                            } else {
                                "Downloading ${source.url}"
                            }
                        )
                    }
                    is DownloadEvent.Progress -> {
                        onEvent(InstallEvent.Downloading(event.bytes, event.totalBytes, resumedFrom))
                        val mb = event.bytes / MB
                        if (mb != lastReportedMb) {
                            lastReportedMb = mb
                            onProgress(progressLine(mb, event.totalBytes))
                        }
                    }
                    is DownloadEvent.Verified -> onProgress(
                        if (event.fromCache) {
                            "Using cached image (${event.bytes / MB} MB, sha256 verified) - no download needed"
                        } else {
                            "Download verified (sha256 ${source.sha256})"
                        }
                    )
                }
            }
        } catch (e: DownloadError) {
            throw SandboxError.ProvisioningFailed(archive.name, e.message.orEmpty(), e)
        }
    }

    /**
     * Unpacks [archive] into [rootfs]; if that fails or is cancelled part-way,
     * removes what was written. [LinuxEnvironment.isReady] only looks for a
     * shell, and a half-unpacked tree can already contain one.
     */
    private suspend fun extractOrWipe(
        archive: File,
        rootfs: File,
        onEvent: (InstallEvent) -> Unit,
    ): TarGzExtractor.Result {
        val context = currentCoroutineContext()
        try {
            val total = archive.length()
            val stream = ObservedInputStream(archive.inputStream().buffered(), context, total) { read, all ->
                onEvent(InstallEvent.Extracting(read, all))
            }
            return stream.use { extractor.extract(it, rootfs) }
        } catch (cause: Throwable) {
            withContext(NonCancellable) { SafeTree.deleteRecursively(rootfs) }
            throw cause
        }
    }

    private fun progressLine(mb: Long, total: Long?): String =
        if (total != null) "Downloaded $mb / ${total / MB} MB" else "Downloaded $mb MB"

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

    /**
     * Adds a real, unprivileged `dev` user (uid/gid [DEFAULT_UID]) that
     * [SandboxShell.interactiveParams] switches to via `su` for interactive
     * shells, so `whoami` and the prompt no longer say root by default.
     *
     * **No longer switched to for interactive shells.** The claim that used to
     * sit here - that permission checks inside a `su`'d shell still resolve to
     * uid 0 under proot - is wrong. Measured on a Xiaomi Pad 6: after
     * `su - dev`, `id -u` reports `1000`, and `apt-get install` fails with
     * `dpkg: error: requested operation requires superuser privilege`. `sudo`
     * did not help either, because [installSudoShim] is a pass-through that
     * assumes its caller is already root. The sandbox could not install
     * anything, so `SandboxShell.interactiveParams` now runs the guest's root
     * shell directly.
     *
     * The user is still created: it costs nothing, and a non-root account is
     * wanted by tools that refuse to run as root. Anything switching to it
     * needs real `sudo` first, not the shim.
     *
     * Written directly rather than via `useradd` so it does not depend on
     * `shadow-utils` being installed for a given preset - every distro rootfs
     * ships `/etc/passwd`, even a minimal one.
     */
    private fun createDefaultUser(rootfs: File) {
        val user = GuestEnvironment.DEFAULT_USER
        val uid = GuestEnvironment.DEFAULT_UID
        val passwd = File(rootfs, "etc/passwd")
        if (!passwd.isFile) return
        if (passwd.readLines().any { it.startsWith("$user:") }) return

        passwd.appendText("$user:x:$uid:$uid::/home/$user:/bin/sh\n")
        File(rootfs, "etc/group").takeIf { it.isFile }
            ?.appendText("$user:x:$uid:\n")
        // No real authentication ever happens - `su` invoked by (fake) root
        // needs no password - so the shadow entry only has to exist, not work.
        File(rootfs, "etc/shadow").takeIf { it.isFile }
            ?.appendText("$user:!:::::::\n")

        val home = File(rootfs, "home/$user").apply { mkdirs() }
        // Matches what `useradd -m` does: without this, `su -` still works
        // but lands in an empty home with no .profile, so the shell falls
        // back to dash's bare built-in prompt instead of the distro's own.
        File(rootfs, "etc/skel").takeIf { it.isDirectory }
            ?.copyRecursively(home, overwrite = false)
    }

    private companion object {
        const val MB = 1024L * 1024

        /**
         * Ceiling on a rootfs download. ubuntu-base is ~30 MB; a pre-baked
         * image with a toolchain is a few hundred. Anything past this is not a
         * rootfs we published and would only fill the tablet's storage.
         */
        const val MAX_ROOTFS_BYTES = 1024L * MB

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
