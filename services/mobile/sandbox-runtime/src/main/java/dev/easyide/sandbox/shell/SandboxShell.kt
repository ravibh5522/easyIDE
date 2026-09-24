package dev.easyide.sandbox.shell

import dev.easyide.sandbox.backend.GuestBind
import dev.easyide.sandbox.backend.GuestEnvironment
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
        extraEnvironment: Map<String, String> = emptyMap(),
        extraBinds: List<GuestBind> = emptyList(),
    ): TerminalProcess {
        val spec = launcher.buildLaunchSpec(
            LaunchRequest(
                rootfs = rootfs,
                hostProjectDir = hostProjectDir,
                guestProjectPath = guestProjectPath,
                extraEnvironment = extraEnvironment,
                extraBinds = extraBinds,
                // Through the guest's own shell so pipes, redirects and globs
                // behave the way the user expects.
                command = listOf(GUEST_SHELL, GUEST_SHELL_FLAG, command),
            )
        )

        val builder = ProcessBuilder(spec.argv)
            .directory(spec.workingDir)
            .redirectErrorStream(true)
        applyEnvironment(builder, spec.environment)

        return TerminalProcess(builder.start(), ioDispatcher)
    }

    /**
     * Pty params that run [argv] instead of an interactive shell (a "command
     * terminal"): the tab shows the command's output and ends when it exits,
     * which is how `sandboxExec` with `output: terminal` and tasks report an
     * exit code.
     */
    fun commandParams(
        argv: List<String>,
        rootfs: File,
        hostProjectDir: File?,
        guestProjectPath: String,
        guestCwd: String?,
        extraEnvironment: Map<String, String>,
        extraBinds: List<GuestBind>,
    ): PtyShellParams = ptyParams(
        LaunchRequest(rootfs, hostProjectDir, guestProjectPath, argv, extraEnvironment, extraBinds, guestCwd),
    )

    /**
     * Params for a real interactive shell, with no `-c` and no command
     * string, so it reads from the pty as an actual interactive shell
     * (prompt, line editing, job control) rather than running one command and
     * exiting. The caller constructs a `com.termux.terminal.TerminalSession`
     * from this; unlike [start], no process is spawned here -
     * `TerminalSession`/JNI does that itself, pty and all, and `clearenv()`s
     * before applying [PtyShellParams.env] the same way [start] does above.
     *
     * Runs the guest shell as proot's fake **root**.
     *
     * This used to run `su - dev` so the prompt would not say `root`, on the
     * documented grounds that permission checks inside a `su`'d shell still
     * resolve to uid 0 under proot. **They do not** - measured on a Xiaomi Pad
     * 6: `id -u` reports `1000` after the switch, and `apt-get install` dies
     * with `dpkg: error: requested operation requires superuser privilege`.
     * `sudo` did not rescue it either: the shim in
     * `RootfsProvisioner.installSudoShim` is a pass-through that assumes its
     * caller is already root, so `apt install` and `sudo apt install` failed
     * alike - which is to say the sandbox could not install anything.
     *
     * proot is not a privilege boundary (decision 0002), so the switch bought
     * no isolation to set against that. Running as the guest's root is what
     * proot-distro and Termux do too.
     */
    fun interactiveParams(
        rootfs: File,
        hostProjectDir: File?,
        guestProjectPath: String,
        extraBinds: List<GuestBind> = emptyList(),
    ): PtyShellParams = ptyParams(
        LaunchRequest(
            rootfs = rootfs,
            hostProjectDir = hostProjectDir,
            guestProjectPath = guestProjectPath,
            extraBinds = extraBinds,
            command = listOf(GUEST_SHELL),
        )
    )

    private fun ptyParams(request: LaunchRequest): PtyShellParams {
        val spec = launcher.buildLaunchSpec(request)
        return PtyShellParams(
            shellPath = spec.argv.first(),
            args = spec.argv.drop(1),
            env = spec.environment + loaderEnvironment(),
            cwd = spec.workingDir.absolutePath,
        )
    }

    /**
     * Starts [command] (a guest argv, no shell string) with three separate
     * plain pipes: no pty, stderr NOT merged into stdout. A language server
     * speaks byte-exact `Content-Length` framing on stdout, which a pty's
     * `\n` -> `\r\n` translation or interleaved log text would corrupt - see
     * docs/extension-sdk/lld/lsp-client.md sec 3.1. Extension `sandboxExec`
     * capture uses the same path: its result contract is `{exitCode, stdout,
     * stderr}`, which [start]'s merged stream cannot satisfy. [guestCwd] is the
     * guest start directory (default: the project mount).
     *
     * The environment is cleared exactly as in [start]: the process sees the
     * launcher's guest defaults, [extraEnvironment], and the proot loader
     * variables, nothing from the Android app process (no git token, decision
     * 0012).
     */
    fun startPiped(
        command: List<String>,
        rootfs: File,
        hostProjectDir: File?,
        guestProjectPath: String,
        extraEnvironment: Map<String, String>,
        extraBinds: List<GuestBind>,
        guestCwd: String? = null,
    ): Process {
        val spec = launcher.buildLaunchSpec(
            LaunchRequest(rootfs, hostProjectDir, guestProjectPath, command, extraEnvironment, extraBinds, guestCwd),
        )
        val builder = ProcessBuilder(spec.argv)
            .directory(spec.workingDir)
            .redirectErrorStream(false)
        applyEnvironment(builder, spec.environment)
        return builder.start()
    }

    private fun applyEnvironment(builder: ProcessBuilder, environment: Map<String, String>) {
        builder.environment().apply {
            // ProcessBuilder inherits the whole Android app process's
            // environment by default - including TMPDIR, which Android points
            // at this app's own cache dir. That path means nothing inside the
            // guest rootfs, and a postinst script that calls mktemp() with it
            // (ca-certificates does) fails outright: "mkstemp: ... No such
            // file or directory". Clearing first means the guest sees exactly
            // the variables named below, nothing leaked from the host.
            clear()
            putAll(environment)
            putAll(loaderEnvironment())
        }
    }

    /**
     * proot's loaders, and libtalloc, which lives beside proot in app storage
     * rather than on the system library path.
     */
    private fun loaderEnvironment(): Map<String, String> = mapOf(
        ENV_PROOT_LOADER to installation.loader.absolutePath,
        ENV_PROOT_LOADER_32 to installation.loader32.absolutePath,
        ENV_LD_LIBRARY_PATH to installation.libraryDir.absolutePath,
    )

    private companion object {
        const val GUEST_SHELL = "/bin/sh"
        const val GUEST_SHELL_FLAG = "-c"
        const val ENV_PROOT_LOADER = "PROOT_LOADER"
        const val ENV_PROOT_LOADER_32 = "PROOT_LOADER_32"
        const val ENV_LD_LIBRARY_PATH = "LD_LIBRARY_PATH"
    }
}
