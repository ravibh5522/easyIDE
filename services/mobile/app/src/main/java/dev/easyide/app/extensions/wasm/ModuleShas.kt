package dev.easyide.app.extensions.wasm

import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.json.stringOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The sha256 of every WASM module, recorded the first time it is loaded and checked by the
 * loader at every later load (lld/wasm-host.md sec 3.1, threat-model M-23: catches naive
 * tampering with an installed version directory, which is immutable once installed).
 *
 * The install record (`state.json`) does not carry a module hash yet, so this is the
 * "record at first load" path of the LLD: `<files>/extensions/wasm-modules.json`, keyed by
 * the module's absolute path (a version directory is never reused for other bytes). An
 * unreadable record file reads as empty, which re-records - the safe direction for
 * availability, and no worse than a first install.
 */
class ModuleShas(private val file: File) {

    private val lock = Any()

    /** The recorded sha256 of [module], recording it now if there is none. */
    fun shaFor(module: File): String = synchronized(lock) {
        val key = module.absolutePath
        val known = load()
        known[key]?.let { return it }
        val sha = sha256(module)
        save(known + (key to sha))
        sha
    }

    /** Forgets modules that no longer exist (uninstalled versions). */
    fun prune() = synchronized(lock) {
        val known = load()
        val live = known.filterKeys { File(it).isFile }
        if (live.size != known.size) save(live)
    }

    private fun load(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val text = try { file.readText() } catch (e: IOException) { return emptyMap() }
        val root = (JsonText.parseStrict(text) as? JsonParse.Ok)?.value as? JsonObject ?: return emptyMap()
        return root.mapNotNull { (k, v) -> v.stringOrNull?.let { k to it } }.toMap()
    }

    /** Best effort: a failed write only means the module is hashed again next time. */
    private fun save(map: Map<String, String>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString())
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: IOException) {
            // Next load records again.
        }
    }

    companion object {
        /** @throws IOException when [f] cannot be read (the activation then fails with the reason). */
        fun sha256(f: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private const val BUFFER = 64 * 1024
    }
}
