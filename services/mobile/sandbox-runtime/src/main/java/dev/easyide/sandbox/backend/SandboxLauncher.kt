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
 * A host directory made visible at [guestPath] inside the sandbox.
 *
 * Both paths reach proot's `-b host:guest` argument, where `:` is the
 * separator, and a root shell's `mount` under chroot; the checks below make a
 * path that would be split or escape its intended location unrepresentable
 * rather than something each launcher has to remember to reject. Not a
 * read-only boundary under either backend (decision 0002).
 */
data class GuestBind(val host: File, val guestPath: String) {
    init {
        require(host.isAbsolute && BIND_SEPARATOR !in host.path) {
            "Bind source must be an absolute path without '$BIND_SEPARATOR': ${host.path}"
        }
        require(guestPath.startsWith("/") && BIND_SEPARATOR !in guestPath) {
            "Bind target must be an absolute guest path without '$BIND_SEPARATOR': $guestPath"
        }
        require(guestPath.split('/').none { it == "." || it == ".." }) {
            "Bind target must not contain '.' or '..' segments: $guestPath"
        }
    }

    private companion object {
        const val BIND_SEPARATOR = ':'
    }
}

/**
 * Supplies the [GuestBind]s for every process launched into an environment.
 * Asked at each launch, so a change on disk (an extension's `current` flip)
 * reaches the next process without any cache to invalidate.
 */
fun interface GuestBindSource {
    fun bindsFor(environmentId: String): List<GuestBind>
}

/**
 * Everything a launcher needs to know about one entry into a sandbox.
 *
 * [hostProjectDir] is bind-mounted at [guestProjectPath] rather than living
 * inside the rootfs, which is what lets a project move between environments -
 * see docs/decision/0005-sandbox-environment-sharing-model.md.
 *
 * [extraBinds] are applied after the project bind, in order; today they carry
 * installed environment extensions to `/opt/easyide/extensions/<id>`.
 *
 * [guestCwd] is where [command] starts inside the guest; null keeps the
 * default (the alias, else the project mount when there is one, else the guest home).
 * Extension actions set it from their `cwd` parameter.
 *
 * [guestAlias] is a second, per-project guest path for the same [hostProjectDir]. `/workspace`
 * is the same string for every project, so path-keyed tools (Claude Code's history, trust and
 * memory, editors' caches) would treat all projects of an environment as one; terminals start
 * in the alias so each project is distinct to them. `/workspace` stays bound because extension
 * paths and language servers are defined against it.
 */
data class LaunchRequest(
    val rootfs: File,
    val hostProjectDir: File?,
    val guestProjectPath: String,
    val command: List<String>,
    val extraEnvironment: Map<String, String> = emptyMap(),
    val extraBinds: List<GuestBind> = emptyList(),
    val guestCwd: String? = null,
    val guestAlias: String? = null,
) {
    init {
        require(guestCwd == null || guestCwd.startsWith("/")) { "Guest cwd must be an absolute guest path: $guestCwd" }
        require(guestAlias == null || (guestAlias.startsWith("/") && ':' !in guestAlias)) {
            "Guest alias must be an absolute guest path without ':': $guestAlias"
        }
    }

    /** Where the command starts when no [guestCwd] is given: the alias, else the project mount, else the guest home (null). */
    val defaultCwd: String? get() = guestAlias ?: hostProjectDir?.let { guestProjectPath }
}

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
    const val DEFAULT_PATH = "/usr/local/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/usr/local/games:/usr/games"

    /**
     * Where per-user installers put their binaries, relative to HOME. Installers such as opencode,
     * bun, rustup and `pipx` edit `~/.bashrc`, which only a login bash reads; listing the
     * directories here means a tool is found in every kind of session (a plain `sh`, a language
     * server, a task) right after it installs.
     */
    val USER_BIN_DIRS = listOf(".opencode/bin", ".local/bin", ".cargo/bin", ".bun/bin", ".npm-global/bin")

    const val DEFAULT_TERM = "xterm-256color"

    /** Lets terminal programs use 24-bit colour instead of falling back to the 256-colour palette. */
    const val DEFAULT_COLORTERM = "truecolor"
    const val COLORTERM = "COLORTERM"
    const val DEFAULT_LANG = "C.UTF-8"

    /**
     * The default account (`GuestAccounts`): uid [DEFAULT_UID] in the root group, home `/root`.
     * Interactive terminals drop to it (`SandboxShell.userShell`) while keeping uid 0 as their saved
     * id, so `sudo` (a shim) can return to it after the password check. Language servers and tasks run as root.
     */
    const val DEFAULT_USER = "dev"
    const val DEFAULT_UID = 1000

    /** The root group: the default user is a member of it, so it can read what root's group owns. */
    const val DEFAULT_GID = 0

    /** The shell every interactive terminal starts (a login shell), when the guest has it. */
    const val LOGIN_SHELL = "/bin/bash"

    /** Where a session with no bound project lands - root's own home, matching a real login. */
    const val GUEST_HOME = "/root"

    fun defaults(home: String): Map<String, String> = mapOf(
        HOME to home,
        PATH to (USER_BIN_DIRS.map { "$home/$it" } + DEFAULT_PATH).joinToString(":"),
        TERM to DEFAULT_TERM,
        COLORTERM to DEFAULT_COLORTERM,
        LANG to DEFAULT_LANG,
    )
}
