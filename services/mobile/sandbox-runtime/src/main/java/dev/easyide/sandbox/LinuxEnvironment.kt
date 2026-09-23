package dev.easyide.sandbox

import android.os.Build
import dev.easyide.sandbox.backend.GuestBind
import dev.easyide.sandbox.backend.GuestBindSource
import dev.easyide.sandbox.bootstrap.ProgressReporter
import dev.easyide.sandbox.bootstrap.ProotInstaller
import dev.easyide.sandbox.bootstrap.RootfsProvisioner
import dev.easyide.sandbox.extensions.EnvironmentExtensionBinds
import dev.easyide.sandbox.model.SandboxImage
import dev.easyide.sandbox.shell.PtyShellParams
import dev.easyide.sandbox.shell.SandboxShell
import dev.easyide.sandbox.shell.ShellRunner
import dev.easyide.sandbox.shell.TerminalProcess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single entry point for "run a command for this project".
 *
 * Picks the Linux userland when one is provisioned and falls back to Android's
 * own shell otherwise, so the terminal is useful before setup and powerful
 * after it. Callers do not need to know which world they are in; [isReady]
 * exists only so the UI can offer to install.
 *
 * @param guestBinds extra binds for user-facing processes ([start],
 *   [interactiveShellParams]) - installed environment extensions at
 *   `/opt/easyide/extensions/<id>`. Setup commands during [install] get none:
 *   a fresh environment has no extensions yet.
 */
class LinuxEnvironment(
    private val paths: SandboxPaths,
    private val prootInstaller: ProotInstaller,
    private val provisioner: RootfsProvisioner,
    private val fallbackShell: ShellRunner,
    private val ioDispatcher: CoroutineDispatcher,
    private val guestBinds: GuestBindSource = EnvironmentExtensionBinds(paths),
) {

    fun rootfsFor(environmentId: String): File = paths.rootfsDir(environmentId)

    /** A rootfs with a shell in it is the cheapest honest readiness check. */
    suspend fun isReady(environmentId: String): Boolean = withContext(ioDispatcher) {
        File(rootfsFor(environmentId), GUEST_SHELL_RELATIVE).exists()
    }

    /**
     * Unpacks [image]'s rootfs into the environment, then runs its setup
     * commands inside it so a preset arrives with its toolchain already there.
     *
     * Setup output is streamed through [onProgress] rather than swallowed: an
     * apt install is minutes of work, and a silent progress bar during it is
     * indistinguishable from a hang.
     */
    suspend fun install(
        environmentId: String,
        image: SandboxImage,
        onProgress: ProgressReporter,
    ): Result<Unit> = runCatching {
        val rootfsArchive = image.rootfsFor(Build.SUPPORTED_ABIS.toList())
            ?: throw SandboxError.ProvisioningFailed(
                environmentId,
                "${image.label} has no rootfs for ${Build.SUPPORTED_ABIS.joinToString()}",
            )

        onProgress("Installing proot...")
        paths.ensureBaseDirs()
        val installation = prootInstaller.ensureInstalled(paths.runtimeDir).getOrThrow()

        provisioner.provision(
            source = rootfsArchive,
            rootfs = rootfsFor(environmentId),
            // Device-level, not per-environment: the image is downloaded once
            // and every later environment extracts from the same file.
            archive = paths.cachedImage(imageIdFor(rootfsArchive.url)),
            onProgress = onProgress,
        ).getOrThrow()

        if (!isReady(environmentId)) {
            throw SandboxError.ProvisioningFailed(environmentId, "rootfs has no $GUEST_SHELL_RELATIVE")
        }

        runSetup(environmentId, image, installation, onProgress)
        onProgress("${image.label} ready.")
    }

    private suspend fun runSetup(
        environmentId: String,
        image: SandboxImage,
        installation: ProotInstaller.Installation,
        onProgress: ProgressReporter,
    ) {
        if (image.setupCommands.isEmpty()) return
        val shell = SandboxShell(installation, ioDispatcher)
        val rootfs = rootfsFor(environmentId)

        image.setupCommands.forEach { command ->
            var exit = runInGuest(shell, rootfs, command, onProgress)
            var attempt = 1

            // proot's --link2symlink hard-link emulation can leave a postinst
            // script's symlink chain transiently broken mid-transaction - seen
            // on device as `ca-certificates`'s postinst failing to exec a
            // perl-shebang script with "not found" while perl's own package is
            // still settling. `dpkg --configure -a` is the standard repair for
            // exactly this (proot-distro and Termux use the same fix). One
            // retry was not always enough on device - the same symlink chain
            // can still be unresolved on the very next attempt - so this
            // retries a bounded number of times rather than once.
            while (exit != 0 && attempt < SETUP_RETRY_LIMIT) {
                attempt++
                onProgress("setup step failed, retrying after dpkg --configure -a (attempt $attempt)...")
                runInGuest(shell, rootfs, "dpkg --configure -a", onProgress)
                exit = runInGuest(shell, rootfs, command, onProgress)
            }

            if (exit != 0) {
                throw SandboxError.ProvisioningFailed(
                    environmentId,
                    "setup step '$command' failed with exit $exit after $attempt attempts",
                )
            }
        }
    }

    private suspend fun runInGuest(
        shell: SandboxShell,
        rootfs: File,
        command: String,
        onProgress: ProgressReporter,
    ): Int {
        onProgress("$ $command")
        return shell.start(
            command = command,
            rootfs = rootfs,
            hostProjectDir = null,
            guestProjectPath = paths.guestProjectPath(),
        ).stream { lines -> lines.forEach(onProgress) }
    }

    /**
     * Starts a command and hands back a live handle. The caller streams output
     * and may write to stdin, so long-running and interactive commands both
     * behave like a terminal rather than a batch job.
     *
     * @throws Exception if proot is unavailable; the caller reports it.
     */
    suspend fun start(
        command: String,
        environmentId: String,
        hostProjectDir: File,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): TerminalProcess {
        if (!isReady(environmentId)) {
            return fallbackShell.start(command, hostProjectDir)
        }
        val installation = prootInstaller.ensureInstalled(paths.runtimeDir).getOrThrow()
        // Cheap and idempotent; keeps environments provisioned by older builds
        // working (DNS, the sudo shim) without a reinstall.
        provisioner.ensureGuestDefaults(rootfsFor(environmentId))
        return SandboxShell(installation, ioDispatcher).start(
            command = command,
            rootfs = rootfsFor(environmentId),
            hostProjectDir = hostProjectDir,
            guestProjectPath = paths.guestProjectPath(),
            extraEnvironment = extraEnvironment,
            extraBinds = bindsFor(environmentId),
        )
    }

    /**
     * Params for a real, pty-backed interactive shell - see
     * [SandboxShell.interactiveParams]. Same proot-ready-or-fallback choice as
     * [start], since a real terminal is strictly better than the line-based
     * one for either backend and the caller should not need to care which
     * world it is in any more than [start]'s callers do.
     */
    suspend fun interactiveShellParams(environmentId: String, hostProjectDir: File): PtyShellParams {
        if (!isReady(environmentId)) {
            return fallbackShell.interactiveParams(hostProjectDir)
        }
        val installation = prootInstaller.ensureInstalled(paths.runtimeDir).getOrThrow()
        provisioner.ensureGuestDefaults(rootfsFor(environmentId))
        return SandboxShell(installation, ioDispatcher).interactiveParams(
            rootfs = rootfsFor(environmentId),
            hostProjectDir = hostProjectDir,
            guestProjectPath = paths.guestProjectPath(),
            extraBinds = bindsFor(environmentId),
        )
    }

    /**
     * Starts a long-lived guest process on plain pipes - see
     * [SandboxShell.startPiped] - for [dev.easyide.sandbox.shell.ServerProcessFactory].
     * Same proot preparation and extension binds as [start], but no fallback
     * to Android's shell: language servers live in the guest, so an
     * environment that is not ready is an error, not a degraded mode.
     *
     * @throws SandboxError.EnvironmentNotReady if the rootfs has no shell yet.
     */
    suspend fun startPiped(
        environmentId: String,
        hostProjectDir: File,
        command: List<String>,
        extraEnvironment: Map<String, String>,
    ): Process {
        if (!isReady(environmentId)) {
            throw SandboxError.EnvironmentNotReady(environmentId, "rootfs has no $GUEST_SHELL_RELATIVE")
        }
        val installation = prootInstaller.ensureInstalled(paths.runtimeDir).getOrThrow()
        provisioner.ensureGuestDefaults(rootfsFor(environmentId))
        val binds = bindsFor(environmentId)
        return withContext(ioDispatcher) {
            SandboxShell(installation, ioDispatcher).startPiped(
                command = command,
                rootfs = rootfsFor(environmentId),
                hostProjectDir = hostProjectDir,
                guestProjectPath = paths.guestProjectPath(),
                extraEnvironment = extraEnvironment,
                extraBinds = binds,
            )
        }
    }

    /** Resolved per launch (filesystem reads), so it runs on the I/O dispatcher. */
    private suspend fun bindsFor(environmentId: String): List<GuestBind> =
        withContext(ioDispatcher) { guestBinds.bindsFor(environmentId) }

    /**
     * Stable cache key from the URL's file name, so two different images never
     * share a cache entry and the same image is only ever fetched once.
     */
    private fun imageIdFor(tarballUrl: String): String =
        tarballUrl.substringAfterLast('/')
            .removeSuffix(".tar.gz")
            .replace(UNSAFE_NAME_CHARS, "_")
            .ifEmpty { FALLBACK_IMAGE_ID }

    private companion object {
        const val GUEST_SHELL_RELATIVE = "bin/sh"
        const val FALLBACK_IMAGE_ID = "rootfs"

        /**
         * Total attempts per setup command, including the first. A safety net
         * for proot's own `--link2symlink` flakiness (see [ProotLauncher]) -
         * most failures during setup were actually a leaked host `TMPDIR`
         * breaking guest postinst scripts, fixed at the source in
         * [dev.easyide.sandbox.shell.SandboxShell]. This stays as a modest
         * margin, not the primary fix.
         */
        const val SETUP_RETRY_LIMIT = 3
        val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}
