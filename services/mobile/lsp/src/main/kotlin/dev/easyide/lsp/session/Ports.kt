package dev.easyide.lsp.session

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/*
 * Ports: what `:lsp` needs from the rest of the app, declared here and implemented in `:app`
 * (hld.md sec 4 rule 2). `:lsp` depends on no project module, so every sandbox, settings and
 * UI interaction crosses one of these interfaces.
 */

/** A guest process with three separate pipes (implemented over `ServerProcessFactory`). */
interface ServerProcessHandle {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
    val isAlive: Boolean

    /** SIGTERM. */
    fun terminate()

    /** SIGKILL. */
    fun kill()

    suspend fun awaitExit(): Int
}

/** The environment is not provisioned; the session goes `Failed(EnvironmentNotReady)`, no retry. */
class EnvironmentNotReadyException(message: String) : Exception(message)

/** Spawns servers inside an environment (hld.md `ServerLauncher`). */
interface ServerLauncher {
    /**
     * Whether `command[0]` exists in the guest (`command -v`).
     *
     * @throws EnvironmentNotReadyException when the environment cannot run anything yet.
     */
    suspend fun isInstalled(key: ServerKey, command: String): Boolean

    /**
     * Starts `argv` with cwd `/workspace`, a clean environment and exactly [env].
     *
     * @throws EnvironmentNotReadyException when the environment cannot run anything yet.
     * @throws Exception any other failure to start (becomes `Failed(SpawnFailed)`).
     */
    suspend fun launch(key: ServerKey, argv: List<String>, env: Map<String, String>): ServerProcessHandle
}

/** Host locations of one project, so `:lsp` can build its [dev.easyide.lsp.workspace.PathMapper]. */
interface ProjectLocator {
    /** Name advertised in `workspaceFolders`. */
    fun projectName(environmentId: String, projectId: String): String
    fun projectDir(environmentId: String, projectId: String): File
    fun rootfsDir(environmentId: String): File

    /** Guest path of the project bind (`SandboxPaths.guestProjectPath()`). */
    val guestWorkspace: String

    /** Android pass-through mounts with no openable host file (`/dev`, `/proc`, `/sys`). */
    val passthroughMounts: List<String>
}

/** One `workspace/configuration` item. */
data class ConfigurationItem(val scopeUri: String?, val section: String?)

/** Settings resolution for servers (arch.md 6.3; lsp-client.md 2.5 `ConfigurationProvider`). */
interface ConfigurationProvider {
    /** One value per item, JSON `null` for an unknown section, in item order. */
    suspend fun configuration(key: ServerKey, items: List<ConfigurationItem>): List<JsonElement>

    /** The resolved value of [section]; emits the current value first, then on every change. */
    fun section(key: ServerKey, section: String): Flow<JsonElement>
}

/** `MessageType` 1..5. */
enum class MessageType(val wire: Int) {
    ERROR(1), WARNING(2), INFO(3), LOG(4), DEBUG(5);

    companion object {
        fun fromWire(v: Int?): MessageType = entries.firstOrNull { it.wire == v } ?: LOG
    }
}

/** Server-initiated UI (snackbars and choice prompts). */
interface LspUi {
    /** `window/showMessage` of type error or warning; lower types only reach the log. */
    fun showMessage(key: ServerKey, type: MessageType, message: String)

    /** `window/showMessageRequest`; returns the chosen action title, or null if dismissed. */
    suspend fun ask(key: ServerKey, type: MessageType, message: String, actions: List<String>): String?
}

/** Forwards a session's log lines to the Extension Log (the session keeps its own ring too). */
fun interface LspLogSink {
    fun append(key: ServerKey, line: String)
}

/** Resident set size of a server's process tree (hld.md `MemoryProbe`), sampled on IO. */
fun interface MemoryProbe {
    /** @return KiB, or null when it cannot be read (process gone, tree not readable). */
    suspend fun rssKb(key: ServerKey, process: ServerProcessHandle): Long?
}

/** Wall clock for crash windows, idle stamps and LRU order; timers use coroutine `delay`. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}
