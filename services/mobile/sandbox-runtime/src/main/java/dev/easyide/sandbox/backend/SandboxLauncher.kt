package dev.easyide.sandbox.backend

import java.io.File

/**
 * How to enter a sandbox: the argv to exec and the environment to hand it.
 * Building this is separated from running it so the argv can be unit-tested
 * without spawning anything.
 */
data class LaunchSpec(
    val argv: List<String>,
    val environment: Map<String, String>,
    val workingDir: File,
)

/**
 * Everything a launcher needs to know about one entry into a sandbox.
 *
 * [hostProjectDir] is bind-mounted at [guestProjectPath] rather than living
 * inside the rootfs, which is what lets a project move between environments -
 * see docs/decision/0005-sandbox-environment-sharing-model.md.
 */
data class LaunchRequest(
    val rootfs: File,
    val hostProjectDir: File?,
    val guestProjectPath: String,
    val command: List<String>,
    val extraEnvironment: Map<String, String> = emptyMap(),
)

/** Builds the argv for one sandbox backend. */
interface SandboxLauncher {
    fun buildLaunchSpec(request: LaunchRequest): LaunchSpec
}

/**
 * Shared environment variables. Kept here so both backends agree, and so no
 * launcher hardcodes them inline.
 */
internal object GuestEnvironment {
    const val HOME = "HOME"
    const val PATH = "PATH"
    const val TERM = "TERM"
    const val LANG = "LANG"

    /** /usr/local first, so the provisioned `sudo` shim is found. */
    const val DEFAULT_PATH = "/usr/local/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
    const val DEFAULT_TERM = "xterm-256color"
    const val DEFAULT_LANG = "C.UTF-8"

    /**
     * The unprivileged user `RootfsProvisioner.createDefaultUser` writes into
     * every rootfs, and that `SandboxShell.interactiveParams` switches
     * interactive shells to via `su` when it is present. Named once here so
     * both agree - see `createDefaultUser`'s doc comment for why this is
     * cosmetic (prompt/`whoami` only), not a real privilege boundary.
     */
    const val DEFAULT_USER = "dev"
    const val DEFAULT_UID = 1000

    /** Where a session with no bound project lands - root's own home, matching a real login. */
    const val GUEST_HOME = "/root"

    fun defaults(home: String): Map<String, String> = mapOf(
        HOME to home,
        PATH to DEFAULT_PATH,
        TERM to DEFAULT_TERM,
        LANG to DEFAULT_LANG,
    )
}
