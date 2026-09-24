package dev.easyide.app.extensions.wasm

import dev.easyide.extwasm.ErrorCode
import dev.easyide.extwasm.HostCallException
import dev.easyide.extwasm.WasmPolicy
import dev.easyide.extwasm.host.FilePort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Host directories behind the guest file system of the open workspace. */
data class GuestRoots(
    /** Mounted at `/workspace`. */
    val project: File,
    /** The environment's rootfs, `/` for every other guest path; null when unknown. */
    val rootfs: File?,
)

/**
 * `fs.*` over the open workspace (lld/wasm-host.md sec 9.2). The host router has already
 * checked `fs.project(read/write)` and, for paths outside `/workspace`, `fs.outsideProject`,
 * on the lexically normalised guest path; this port maps it to the host and refuses
 * (E_CAPABILITY) any path whose canonical host file leaves its root, which only it can see:
 * a symlink inside the project pointing elsewhere, or a rootfs link to an absolute target
 * (those resolve against the device root, so they are refused rather than followed).
 *
 * `fs.watch` records globs; `fs.changed` is posted for saves and buffer writes the app itself
 * makes ([changed]). Changes made by processes in the environment are not observed yet.
 */
class WasmFilePort(
    private val roots: () -> GuestRoots?,
    /** Largest file `fs.read` returns (the message limit bounds it anyway). */
    private val maxReadBytes: () -> Int,
    private val io: CoroutineDispatcher,
) : FilePort {

    private val watches = ConcurrentHashMap<String, MutableSet<String>>()

    override suspend fun read(path: String, args: JsonObject): JsonElement = withContext(io) {
        val f = resolve(path)
        if (!f.isFile) throw HostCallException(ErrorCode.E_NOT_FOUND, "$path is not a file")
        if (f.length() > maxReadBytes()) throw HostCallException(ErrorCode.E_LIMIT, "$path is larger than ${maxReadBytes()} bytes")
        val bytes = io { f.readBytes() }
        if (encoding(args) == BASE64) JsonPrimitive(Base64.getEncoder().encodeToString(bytes)) else JsonPrimitive(String(bytes, Charsets.UTF_8))
    }

    override suspend fun write(extensionId: String, path: String, args: JsonObject) = withContext(io) {
        val text = (args[TEXT] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw HostCallException(ErrorCode.E_ARGS, "\"text\" must be a string")
        val bytes = if (encoding(args) == BASE64) {
            try { Base64.getDecoder().decode(text) } catch (e: IllegalArgumentException) { throw HostCallException(ErrorCode.E_ARGS, "\"text\" is not base64") }
        } else {
            text.toByteArray(Charsets.UTF_8)
        }
        val f = resolve(path)
        if (f.isDirectory) throw HostCallException(ErrorCode.E_ARGS, "$path is a directory")
        io {
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
        }
    }

    override suspend fun stat(path: String): JsonElement? = withContext(io) {
        val f = resolve(path)
        if (!f.exists()) return@withContext null
        buildJsonObject {
            put(TYPE, if (f.isDirectory) DIRECTORY else FILE)
            put(SIZE, if (f.isFile) f.length() else 0L)
            put(MTIME, f.lastModified())
        }
    }

    override suspend fun list(path: String): JsonElement = withContext(io) {
        val dir = resolve(path)
        if (!dir.isDirectory) throw HostCallException(ErrorCode.E_NOT_FOUND, "$path is not a directory")
        JsonArray(dir.listFiles().orEmpty().sortedBy { it.name }.map { c ->
            buildJsonObject { put(NAME, c.name); put(TYPE, if (c.isDirectory) DIRECTORY else FILE) }
        })
    }

    override suspend fun delete(extensionId: String, path: String) = withContext(io) {
        val f = resolve(path)
        if (!f.exists()) throw HostCallException(ErrorCode.E_NOT_FOUND, "$path does not exist")
        if (f == roots()?.project?.canonicalFile) throw HostCallException(ErrorCode.E_ARGS, "the project root cannot be deleted")
        if (!io { f.deleteRecursively() }) throw HostCallException(ErrorCode.E_INTERNAL, "could not delete $path")
    }

    override suspend fun rename(extensionId: String, from: String, to: String) = withContext(io) {
        val src = resolve(from)
        val dst = resolve(to)
        if (!src.exists()) throw HostCallException(ErrorCode.E_NOT_FOUND, "$from does not exist")
        if (dst.exists()) throw HostCallException(ErrorCode.E_ARGS, "$to already exists")
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) throw HostCallException(ErrorCode.E_INTERNAL, "could not rename $from")
    }

    override suspend fun watch(extensionId: String, glob: String) {
        watches.getOrPut(extensionId) { ConcurrentHashMap.newKeySet() } += glob
    }

    override fun unwatchAll(extensionId: String) {
        watches.remove(extensionId)
    }

    /** Extensions whose `fs.watch` globs match guest [path] (relative globs are project-relative). */
    fun watchersOf(path: String): List<String> = watches.entries.filter { (_, globs) -> globs.any { matches(it, path) } }.map { it.key }

    /**
     * Guest path -> canonical host file inside its root.
     * @throws HostCallException E_UNAVAILABLE with no workspace, E_CAPABILITY on an escape.
     */
    fun resolve(guestPath: String): File {
        val r = roots() ?: throw HostCallException(ErrorCode.E_UNAVAILABLE, "no workspace is open")
        val inProject = guestPath == WasmPolicy.PROJECT_ROOT || guestPath.startsWith(WasmPolicy.PROJECT_ROOT + "/")
        val root = if (inProject) r.project else r.rootfs ?: throw HostCallException(ErrorCode.E_UNAVAILABLE, "the environment's files are not available")
        val relative = if (inProject) guestPath.removePrefix(WasmPolicy.PROJECT_ROOT) else guestPath
        val canonicalRoot = root.canonicalFile
        // Canonicalisation resolves every existing symlink on the way (a real I/O boundary).
        val target = try {
            File(canonicalRoot, relative.trimStart('/')).canonicalFile
        } catch (e: IOException) {
            throw HostCallException(ErrorCode.E_INTERNAL, "cannot resolve $guestPath")
        }
        if (target != canonicalRoot && !target.path.startsWith(canonicalRoot.path + File.separator)) {
            throw HostCallException(ErrorCode.E_CAPABILITY, "$guestPath resolves outside its allowed root")
        }
        return target
    }

    private fun encoding(args: JsonObject): String? = (args[ENCODING] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw HostCallException(ErrorCode.E_INTERNAL, e.message ?: "I/O error")
    }

    private fun matches(glob: String, path: String): Boolean {
        val (pattern, subject) = if (glob.startsWith("/")) {
            glob to path
        } else {
            glob to path.removePrefix(WasmPolicy.PROJECT_ROOT + "/").takeIf { it != path }.orEmpty()
        }
        if (subject.isEmpty()) return false
        val fs = FileSystems.getDefault()
        return listOf(pattern, pattern.removePrefix(DOUBLE_STAR)).distinct().any { fs.getPathMatcher("glob:$it").matches(Paths.get(subject)) }
    }

    private companion object {
        const val TEXT = "text"
        const val ENCODING = "encoding"
        const val BASE64 = "base64"
        const val TYPE = "type"
        const val SIZE = "size"
        const val MTIME = "mtime"
        const val NAME = "name"
        const val FILE = "file"
        const val DIRECTORY = "directory"
        const val DOUBLE_STAR = "**/"
    }
}
