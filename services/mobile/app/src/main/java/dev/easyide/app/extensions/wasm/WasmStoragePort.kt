package dev.easyide.app.extensions.wasm

import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.settings.RuntimeScope
import dev.easyide.extwasm.ErrorCode
import dev.easyide.extwasm.HostCallException
import dev.easyide.extwasm.host.StoragePort
import dev.easyide.extwasm.host.StorageScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * `storage.*` (lld/wasm-host.md sec 13): one JSON object per extension and scope at
 * `<root>/<extId>/{global,env-<envId>,project-<projectId>}.json`, written temp + fsync +
 * atomic rename. The quota (`extensions.storage.quotaKb`) is checked by the host's
 * `storage.set` against [usedBytes] before [set] runs, so this port only has to count
 * exactly. Environment and project scopes need an open workspace (E_UNAVAILABLE otherwise).
 *
 * Every path is derived from a validated extension id and an id the app itself assigned, so
 * a guest cannot address another extension's files.
 */
class WasmStoragePort(
    private val root: File,
    private val runtimeScope: () -> RuntimeScope,
    private val io: CoroutineDispatcher,
) : StoragePort {

    private val lock = Mutex()

    override suspend fun get(extensionId: String, scope: StorageScope, key: String): JsonElement? =
        lock.withLock { read(file(extensionId, scope))[key] }

    override suspend fun set(extensionId: String, scope: StorageScope, key: String, value: JsonElement): Unit = lock.withLock {
        val f = file(extensionId, scope)
        write(f, JsonObject(read(f) + (key to value)))
    }

    override suspend fun delete(extensionId: String, scope: StorageScope, key: String): Unit = lock.withLock {
        val f = file(extensionId, scope)
        val current = read(f)
        if (key in current) write(f, JsonObject(current - key))
    }

    override suspend fun keys(extensionId: String, scope: StorageScope): List<String> =
        lock.withLock { read(file(extensionId, scope)).keys.sorted() }

    /** Every scope file of the extension, including other environments and projects: the quota is per extension. */
    override suspend fun usedBytes(extensionId: String): Long = lock.withLock {
        withContext(io) {
            dir(extensionId).listFiles { f -> f.isFile && f.name.endsWith(JSON) }.orEmpty().sumOf { f ->
                read(f).entries.sumOf { (k, v) -> StoragePort.entryBytes(k, v) }
            }
        }
    }

    private fun dir(extensionId: String): File {
        val id = ExtensionId.parse(extensionId) ?: throw HostCallException(ErrorCode.E_INTERNAL, "invalid extension id")
        return File(root, id.value)
    }

    private fun file(extensionId: String, scope: StorageScope): File {
        val rs = runtimeScope()
        val name = when (scope) {
            StorageScope.GLOBAL -> GLOBAL
            StorageScope.ENVIRONMENT -> ENV_PREFIX + (rs.envId ?: unavailable(scope))
            StorageScope.PROJECT -> PROJECT_PREFIX + (rs.projectId ?: unavailable(scope))
        }
        return File(dir(extensionId), name + JSON)
    }

    private fun unavailable(scope: StorageScope): Nothing =
        throw HostCallException(ErrorCode.E_UNAVAILABLE, "no workspace is open for ${scope.wire} storage")

    /** I/O boundary: a missing file is empty; an unreadable or corrupt one fails the call instead of losing data on the next write. */
    private suspend fun read(f: File): JsonObject = withContext(io) {
        if (!f.isFile) return@withContext EMPTY
        val text = try {
            f.readText()
        } catch (e: IOException) {
            throw HostCallException(ErrorCode.E_INTERNAL, "storage unreadable: ${e.message}")
        }
        (JsonText.parseStrict(text) as? JsonParse.Ok)?.value as? JsonObject
            ?: throw HostCallException(ErrorCode.E_INTERNAL, "storage file ${f.name} is corrupt")
    }

    private suspend fun write(f: File, content: JsonObject): Unit = withContext(io) {
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + TMP)
            FileOutputStream(tmp).use { out ->
                out.write(content.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            throw HostCallException(ErrorCode.E_INTERNAL, "storage not written: ${e.message}")
        }
    }

    private companion object {
        val EMPTY = JsonObject(emptyMap())
        const val GLOBAL = "global"
        const val ENV_PREFIX = "env-"
        const val PROJECT_PREFIX = "project-"
        const val JSON = ".json"
        const val TMP = ".tmp"
    }
}
