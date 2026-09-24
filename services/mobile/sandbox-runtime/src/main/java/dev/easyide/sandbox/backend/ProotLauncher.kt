package dev.easyide.sandbox.backend

import dev.easyide.sandbox.SandboxPaths
import java.io.File

/**
 * Default backend on every device. proot rewrites path arguments via ptrace, so
 * no root and no kernel namespace is involved - and correspondingly it is not a
 * security boundary. See docs/sandbox-runtime/arch.md SS3.
 *
 * @param prootBinary absolute path to the proot executable inside app-private
 *   storage. Injected rather than hardcoded because it lives wherever the
 *   bootstrap unpacked it.
 */
class ProotLauncher(
    private val prootBinary: File,
    private val loaderDir: File? = null,
) : SandboxLauncher {

    override fun buildLaunchSpec(request: LaunchRequest): LaunchSpec {
        val argv = buildList {
            add(prootBinary.absolutePath)
            add(ARG_KILL_ON_EXIT)
            addAll(HARD_LINK_EMULATION)
            add(ARG_ROOT_ID)
            addAll(listOf(ARG_ROOTFS, request.rootfs.absolutePath))

            // Android's own /dev, /proc and /sys are passed through; proot
            // cannot synthesize them and most tooling breaks without them.
            PASSTHROUGH_MOUNTS.forEach { mount ->
                addAll(listOf(ARG_BIND, mount))
            }

            request.hostProjectDir?.let { host ->
                addAll(listOf(ARG_BIND, "${host.absolutePath}:${request.guestProjectPath}"))
            }

            // proot creates a missing guest target itself, so no mkdir here.
            request.extraBinds.forEach { bind ->
                addAll(listOf(ARG_BIND, "${bind.host.absolutePath}:${bind.guestPath}"))
            }

            addAll(listOf(ARG_CWD, request.hostProjectDir?.let { request.guestProjectPath } ?: GuestEnvironment.GUEST_HOME))
            addAll(request.command)
        }

        val environment = buildMap {
            putAll(GuestEnvironment.defaults(GuestEnvironment.GUEST_HOME))
            loaderDir?.let { put(ENV_PROOT_LOADER, it.absolutePath) }
            put(ENV_PROOT_TMP, request.rootfs.parentFile?.absolutePath ?: request.rootfs.absolutePath)
            putAll(request.extraEnvironment)
        }

        return LaunchSpec(argv = argv, environment = environment, workingDir = request.rootfs)
    }

    private companion object {
        const val ARG_ROOTFS = "-r"
        const val ARG_BIND = "-b"
        const val ARG_CWD = "-w"
        const val ARG_ROOT_ID = "-0"
        const val ARG_KILL_ON_EXIT = "--kill-on-exit"

        const val ENV_PROOT_LOADER = "PROOT_LOADER"
        const val ENV_PROOT_TMP = "PROOT_TMP_DIR"

        val PASSTHROUGH_MOUNTS = SandboxPaths.PASSTHROUGH_MOUNTS

        /**
         * Android denies `link(2)` in app-private storage, and dpkg hard-links
         * every file it is about to replace as a backup. Without this it fails
         * with "unable to make backup link of './usr/bin/perl'" and aborts the
         * whole transaction - measured on a Xiaomi Pad 6, Android 13.
         *
         * The three go together: `--link2symlink` emulates hard links with
         * symlinks, `-H` hides the `.proot.l2s.*` bookkeeping files it creates
         * so they never show up in a directory listing, and `-L` makes `lstat`
         * report the target's size for them instead of the link's.
         */
        val HARD_LINK_EMULATION = listOf("--link2symlink", "-H", "-L")
    }
}
