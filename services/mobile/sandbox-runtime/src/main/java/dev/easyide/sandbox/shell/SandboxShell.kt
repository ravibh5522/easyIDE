package dev.easyide.sandbox.shell

import dev.easyide.sandbox.backend.LaunchRequest
import dev.easyide.sandbox.backend.ProotLauncher
import dev.easyide.sandbox.bootstrap.ProotInstaller
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File

/**
 * Starts a command inside a provisioned rootfs via proot, so `apt`, `dpkg` and
 * everything else in the distro behave normally.
 *
 * Kept separate from [ShellRunner] so it is obvious at the call site which
 * world a command runs in - Android's toybox, or the Linux userland.
 */
class SandboxShell(
    private val installation: ProotInstaller.Installation,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val launcher = ProotLauncher(
        prootBinary = installation.prootBinary,
        loaderDir = installation.loader.parentFile,
    )

    fun start(
        command: String,
        rootfs: File,
        hostProjectDir: File?,
        guestProjectPath: String,
    ): TerminalProcess {
        val spec = launcher.buildLaunchSpec(
            LaunchRequest(
                rootfs = rootfs,
                hostProjectDir = hostProjectDir,
                guestProjectPath = guestProjectPath,
                // Through the guest's own shell so pipes, redirects and globs
                // behave the way the user expects.
                command = listOf(GUEST_SHELL, GUEST_SHELL_FLAG, command),
            )
        )

        val builder = ProcessBuilder(spec.argv)
            .directory(spec.workingDir)
            .redirectErrorStream(true)

        builder.environment().apply {
            // ProcessBuilder inherits the whole Android app process's
            // environment by default - including TMPDIR, which Android points
            // at this app's own cache dir. That path means nothing inside the
            // guest rootfs, and a postinst script that calls mktemp() with it
            // (ca-certificates does) fails outright: "mkstemp: ... No such
            // file or directory". Clearing first means the guest sees exactly
            // the variables named below, nothing leaked from the host.
            clear()
            putAll(spec.environment)
            put(ENV_PROOT_LOADER, installation.loader.absolutePath)
            put(ENV_PROOT_LOADER_32, installation.loader32.absolutePath)
            // proot links against libtalloc, which lives beside it in app
            // storage rather than on the system library path.
            put(ENV_LD_LIBRARY_PATH, installation.libraryDir.absolutePath)
        }

        return TerminalProcess(builder.start(), ioDispatcher)
    }

    private companion object {
        const val GUEST_SHELL = "/bin/sh"
        const val GUEST_SHELL_FLAG = "-c"
        const val ENV_PROOT_LOADER = "PROOT_LOADER"
        const val ENV_PROOT_LOADER_32 = "PROOT_LOADER_32"
        const val ENV_LD_LIBRARY_PATH = "LD_LIBRARY_PATH"
    }
}
