package dev.easyide.app.lsp

import dev.easyide.lsp.session.EnvironmentNotReadyException
import dev.easyide.lsp.session.MemoryProbe
import dev.easyide.lsp.session.ServerKey
import dev.easyide.lsp.session.ServerLauncher
import dev.easyide.lsp.session.ServerProcessHandle
import dev.easyide.sandbox.SandboxError
import dev.easyide.sandbox.SandboxPaths
import dev.easyide.sandbox.shell.ServerLaunch
import dev.easyide.sandbox.shell.ServerProcess
import dev.easyide.sandbox.shell.ServerProcessFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * A server process started by [SandboxServerLauncher], with what [ProcMemoryProbe] needs to
 * find its pid. The pid is looked up lazily and cached: the proot process appears in `/proc`
 * a moment after `ProcessBuilder.start` returns, and the first RSS sample is seconds later.
 */
class SandboxServerHandle internal constructor(
    private val process: ServerProcess,
    val match: ProcMatch,
) : ServerProcessHandle {
    override val stdin: OutputStream get() = process.stdin
    override val stdout: InputStream get() = process.stdout
    override val stderr: InputStream get() = process.stderr
    override val isAlive: Boolean get() = process.isAlive

    override fun terminate() = process.terminate()

    override fun kill() = process.kill()

    override suspend fun awaitExit(): Int = process.awaitExit()

    /** The proot process once found; checked against its start time on every use. */
    @Volatile internal var found: ProcEntry? = null
}

/**
 * [ServerLauncher] over the sandbox's single spawn point, [ServerProcessFactory] (R-ENG-14).
 * Servers run with the user's permissions inside the environment; proot does not isolate
 * them (decision 0002).
 */
class SandboxServerLauncher(
    private val factory: ServerProcessFactory,
    private val paths: SandboxPaths,
    /** The app's own pid (`android.os.Process.myPid()`): proot is its direct child. */
    private val appPid: Int,
) : ServerLauncher {

    override suspend fun isInstalled(key: ServerKey, command: String): Boolean = boundary(key) {
        factory.isInstalled(key.environmentId, projectDir(key), command)
    }

    override suspend fun launch(key: ServerKey, argv: List<String>, env: Map<String, String>): ServerProcessHandle = boundary(key) {
        val dir = projectDir(key)
        val process = factory.spawn(ServerLaunch(key.environmentId, dir, argv, env))
        val bind = "${dir.absolutePath}:${paths.guestProjectPath()}"
        SandboxServerHandle(process, ProcMatch(appPid, ServerProcessFactory.guestArgv(argv), bind))
    }

    private fun projectDir(key: ServerKey): File = paths.projectDir(key.projectId)

    /** The sandbox's "not ready" becomes the port's, so the session stops retrying. */
    private inline fun <T> boundary(key: ServerKey, block: () -> T): T = try {
        block()
    } catch (e: SandboxError.EnvironmentNotReady) {
        throw EnvironmentNotReadyException(e.message ?: "environment ${key.environmentId} is not ready")
    }
}

/**
 * RSS of a server's process tree from `/proc` (lsp-lifecycle.md 3.1): proot plus every guest
 * process under it. Null when the tree cannot be read - the process is gone, or it is not
 * ours to read (the chroot backend runs it as root) - and the sampler then skips it.
 */
class ProcMemoryProbe(private val proc: ProcTree = ProcTree()) : MemoryProbe {

    override suspend fun rssKb(key: ServerKey, process: ServerProcessHandle): Long? {
        val handle = process as? SandboxServerHandle ?: return null
        if (!handle.isAlive) return null
        val all = proc.entries()
        val cached = handle.found?.takeIf { f -> all.any { it.pid == f.pid && it.startTicks == f.startTicks } }
        val root = cached ?: all.filter(handle.match::matches).maxByOrNull { it.startTicks } ?: return null
        handle.found = root
        return proc.treeRssKb(root.pid, all)
    }
}
