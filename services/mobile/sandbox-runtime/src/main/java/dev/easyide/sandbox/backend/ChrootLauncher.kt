package dev.easyide.sandbox.backend

import java.io.File

/**
 * Opt-in backend for rooted devices only. Faster than proot (no ptrace tracer
 * in the syscall path) but it runs the whole process tree as real root, outside
 * Android's SELinux app domain - a performance win and a security downgrade,
 * never the automatic default. See
 * docs/decision/0002-sandbox-backend-proot-default-chroot-optin.md.
 *
 * The mounts and chroot are issued as a single `su -c` script because each
 * `su` invocation is a separate privileged shell; splitting them would lose the
 * mount namespace between steps.
 */
class ChrootLauncher(
    private val suBinary: String = DEFAULT_SU,
    private val busyboxBinary: String = DEFAULT_BUSYBOX,
) : SandboxLauncher {

    override fun buildLaunchSpec(request: LaunchRequest): LaunchSpec {
        val rootfs = request.rootfs.absolutePath
        val script = buildList {
            PASSTHROUGH_MOUNTS.forEach { (source, target, type) ->
                add(mountCommand(source, "$rootfs$target", type))
            }
            request.hostProjectDir?.let { host ->
                add(mkdirCommand("$rootfs${request.guestProjectPath}"))
                add(bindCommand(host.absolutePath, "$rootfs${request.guestProjectPath}"))
            }
            request.extraBinds.forEach { bind ->
                add(mkdirCommand("$rootfs${bind.guestPath}"))
                add(bindCommand(bind.host.absolutePath, "$rootfs${bind.guestPath}"))
            }
            add(chrootCommand(rootfs, request.guestCwd?.let { withCwd(it, request.command) } ?: request.command))
        }.joinToString(separator = "\n")

        return LaunchSpec(
            argv = listOf(suBinary, SU_COMMAND_FLAG, script),
            environment = GuestEnvironment.defaults(GUEST_HOME) + request.extraEnvironment,
            workingDir = request.rootfs,
        )
    }

    private fun mountCommand(source: String, target: String, type: String?): String =
        if (type == null) {
            "$busyboxBinary mount -o bind ${source.shellQuote()} ${target.shellQuote()}"
        } else {
            "$busyboxBinary mount -t $type $source ${target.shellQuote()}"
        }

    private fun bindCommand(source: String, target: String): String =
        "$busyboxBinary mount -o bind ${source.shellQuote()} ${target.shellQuote()}"

    private fun mkdirCommand(target: String): String =
        "$busyboxBinary mkdir -p ${target.shellQuote()}"

    /**
     * `busybox chroot` has no working-directory option, so the guest's own shell
     * changes into [cwd] and then execs the argv unchanged ("$0" is [cwd], "$@"
     * the command), which keeps every argument one word with no re-quoting.
     */
    private fun withCwd(cwd: String, command: List<String>): List<String> =
        listOf(GUEST_SHELL, GUEST_SHELL_FLAG, CD_THEN_EXEC, cwd) + command

    private fun chrootCommand(rootfs: String, command: List<String>): String {
        val quoted = command.joinToString(" ") { it.shellQuote() }
        return "$busyboxBinary chroot ${rootfs.shellQuote()} $quoted"
    }

    private companion object {
        const val DEFAULT_SU = "su"
        const val DEFAULT_BUSYBOX = "busybox"
        const val SU_COMMAND_FLAG = "-c"
        const val GUEST_HOME = "/root"
        const val GUEST_SHELL = "/bin/sh"
        const val GUEST_SHELL_FLAG = "-c"
        const val CD_THEN_EXEC = "cd \"$0\" && exec \"$@\""

        /** source, guest target, filesystem type (null means bind mount). */
        val PASSTHROUGH_MOUNTS = listOf(
            Triple("/dev", "/dev", null),
            Triple("proc", "/proc", "proc"),
            Triple("sysfs", "/sys", "sysfs"),
        )
    }
}

/**
 * Single-quote for POSIX sh. Paths here are app-generated, but they still reach
 * a root shell, so quoting is not optional - an unquoted path containing a
 * space or `;` would execute as a separate privileged command.
 */
internal fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"
