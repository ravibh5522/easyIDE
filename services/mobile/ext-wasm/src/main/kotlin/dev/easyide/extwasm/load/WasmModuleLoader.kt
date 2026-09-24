package dev.easyide.extwasm.load

import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.MemoryLimits
import dev.easyide.extwasm.ModuleRejectedException
import dev.easyide.extwasm.WasmLimits
import dev.easyide.extwasm.WasmPolicy
import dev.easyide.extwasm.binary.Meter
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A validated, metered, parsed module ready to instantiate. */
class LoadedModule internal constructor(
    internal val module: WasmModule,
    internal val memoryLimits: MemoryLimits,
    internal val fuelGlobalIndex: Int,
    /** sha256 of the original (unmetered) bytes, as recorded at install. */
    val sha256: String,
)

private class ParsedEntry(val module: WasmModule, val fuelGlobalIndex: Int)

/**
 * Load pipeline of wasm-host.md sec 3.1: size check, sha256 against the value recorded at
 * install (threat-model M-23, catches naive tampering only), metering pass, Chicory parse,
 * static validation.
 *
 * The pass runs on the raw bytes and Chicory parses only the metered result: the LLD
 * pipeline parses twice, but parsing is the dominant load cost on ART (~0.3 ms/KB in the
 * 2026-09-24 spike) and the metering pass rejects malformed code on its own.
 *
 * [cacheDir] holds metered bytes (`<key>.wasm` + `<key>.sha256` sidecar). It is an
 * optimisation that is never trusted: a sidecar mismatch recomputes. Parsed modules are kept
 * in an in-memory LRU of [WasmPolicy.MODULE_CACHE_ENTRIES].
 */
class WasmModuleLoader(private val cacheDir: File) {

    private val parsed = object : LinkedHashMap<String, ParsedEntry>(WasmPolicy.MODULE_CACHE_ENTRIES, LRU_LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedEntry>) =
            size > WasmPolicy.MODULE_CACHE_ENTRIES
    }

    /**
     * @throws ModuleRejectedException with a user-facing reason for every refusal.
     * @throws IOException when the module file cannot be read.
     */
    fun load(file: File, expectedSha256: String, limits: WasmLimits): LoadedModule {
        val size = file.length()
        if (size > limits.maxModuleBytes) {
            throw ModuleRejectedException("module is $size bytes, the limit is ${limits.maxModuleBytes} (extensions.wasm.maxModuleMb)")
        }
        val original = file.readBytes()
        val sha = sha256Hex(original)
        if (!sha.equals(expectedSha256, ignoreCase = true)) {
            throw ModuleRejectedException("module sha256 $sha does not match the installed record $expectedSha256")
        }
        val key = "$sha-m${Meter.VERSION}"
        val entry = synchronized(parsed) { parsed[key] } ?: parse(key, original).also { e ->
            synchronized(parsed) { parsed[key] = e }
        }
        val memory = ModuleValidator.validate(entry.module, limits.maxMemoryPages)
        return LoadedModule(entry.module, memory, entry.fuelGlobalIndex, sha)
    }

    /** Drops parsed modules (memory pressure). The on-disk cache stays. */
    fun trimMemory() {
        synchronized(parsed) { parsed.clear() }
    }

    /** Deletes cached metered bytes whose original sha256 is not in [liveSha256s]. */
    fun pruneDiskCache(liveSha256s: Set<String>) {
        val live = liveSha256s.map { it.lowercase() }.toSet()
        cacheDir.listFiles()?.forEach { f ->
            if (f.name.substringBefore("-m") !in live) f.delete()
        }
    }

    private fun parse(key: String, original: ByteArray): ParsedEntry {
        val (bytes, fuelIndex) = cachedMetered(key) ?: meterAndStore(key, original)
        // Parsing untrusted bytes is a real boundary: Chicory reports most problems as
        // ChicoryException, but a hostile binary must never surface as anything else.
        val module = try {
            Parser.parse(bytes)
        } catch (e: RuntimeException) {
            throw ModuleRejectedException("invalid wasm module: ${e.message}")
        }
        return ParsedEntry(module, fuelIndex)
    }

    private fun cachedMetered(key: String): Pair<ByteArray, Int>? {
        val data = File(cacheDir, "$key.wasm")
        val side = File(cacheDir, "$key.sha256")
        return try {
            if (!data.isFile || !side.isFile) return null
            val (sha, index) = side.readText().trim().split(' ').takeIf { it.size == 2 } ?: return null
            val bytes = data.readBytes()
            val fuelIndex = index.toIntOrNull() ?: return null
            if (sha256Hex(bytes) == sha) bytes to fuelIndex else null
        } catch (e: IOException) {
            null
        }
    }

    private fun meterAndStore(key: String, original: ByteArray): Pair<ByteArray, Int> {
        val metered = Meter.instrument(original)
        try {
            cacheDir.mkdirs()
            writeAtomically(File(cacheDir, "$key.wasm"), metered.bytes)
            writeAtomically(File(cacheDir, "$key.sha256"), "${sha256Hex(metered.bytes)} ${metered.fuelGlobalIndex}".toByteArray())
        } catch (e: IOException) {
            // A read-only or full disk only costs the next load a re-meter.
        }
        return metered.bytes to metered.fuelGlobalIndex
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("rename to $target failed")
        }
    }

    private companion object {
        const val LRU_LOAD_FACTOR = 0.75f

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
