package dev.easyide.extwasm.runtime

import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.Instance
import dev.easyide.extwasm.WasmPolicy
import dev.easyide.extwasm.load.AbiV1
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A guest buffer that broke ABI v1 rules (bounds, length prefix, UTF-8, JSON, `v`). */
internal class AbiViolation(message: String) : Exception(message)

/**
 * Bounds-checked access to one instance's linear memory in ABI v1 buffer formats
 * (wasm-host.md sec 6). Every pointer and length comes from the guest and is checked
 * against the current memory size before Chicory is asked to touch it, so a hostile guest
 * gets [AbiViolation] and never an exception from inside the runtime.
 *
 * Only used on the instance's worker thread.
 */
internal class GuestMemory(private val instance: Instance, private val maxMessageBytes: Int) {

    private val alloc: ExportFunction = instance.export(AbiV1.ALLOC)
    private val free: ExportFunction = instance.export(AbiV1.FREE)

    private val size: Long get() = instance.memory().pages().toLong() * PAGE_BYTES

    /** Reads a raw (unprefixed) request the guest passed to `host_call`. */
    fun readRequest(ptr: Int, len: Int): JsonObject {
        if (len < 0 || len > maxMessageBytes) throw AbiViolation("request length $len outside 0..$maxMessageBytes")
        return parse(read(Integer.toUnsignedLong(ptr), len))
    }

    /**
     * Reads a `[u32 LE length][JSON]` buffer the guest returned, then frees it with
     * `free(ptr, 4 + length)` as the receiver must.
     */
    fun takePrefixed(ptr: Int): JsonObject {
        val len = Integer.toUnsignedLong(readI32(ptr))
        if (len > maxMessageBytes) throw AbiViolation("reply length $len over the $maxMessageBytes byte limit")
        val body = read(Integer.toUnsignedLong(ptr) + PREFIX_BYTES, len.toInt())
        free.apply(ptr.toLong(), PREFIX_BYTES + len)
        return parse(body)
    }

    /** Writes a raw input message into a guest-allocated buffer; the guest owns and frees it. */
    fun writeInput(json: ByteArray): Pair<Int, Int> {
        val ptr = allocate(json.size)
        instance.memory().write(ptr, json)
        return ptr to json.size
    }

    /** Writes a `host_call` reply as `[u32 LE length][JSON]`; the guest frees it. */
    fun writePrefixed(json: ByteArray): Int {
        val ptr = allocate(PREFIX_BYTES + json.size)
        instance.memory().writeI32(ptr, json.size)
        instance.memory().write(ptr + PREFIX_BYTES, json)
        return ptr
    }

    private fun allocate(n: Int): Int {
        val ptr = alloc.apply(n.toLong())[0].toInt()
        checkRange(Integer.toUnsignedLong(ptr), n.toLong(), "alloc($n) returned")
        return ptr
    }

    private fun readI32(ptr: Int): Int {
        checkRange(Integer.toUnsignedLong(ptr), PREFIX_BYTES.toLong(), "length prefix at")
        return instance.memory().readInt(ptr)
    }

    private fun read(ptr: Long, len: Int): ByteArray {
        checkRange(ptr, len.toLong(), "buffer at")
        return instance.memory().readBytes(ptr.toInt(), len)
    }

    private fun checkRange(ptr: Long, len: Long, what: String) {
        if (ptr < 0 || ptr + len > size) throw AbiViolation("$what $ptr (+$len) is outside guest memory ($size bytes)")
    }

    private fun parse(bytes: ByteArray): JsonObject {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw AbiViolation("message is not valid UTF-8")
        }
        val obj = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            throw AbiViolation("message is not valid JSON: ${e.message}")
        } ?: throw AbiViolation("message must be a JSON object")
        val v = (obj["v"] as? JsonPrimitive)?.intOrNull
        if (v != WasmPolicy.ABI_VERSION) throw AbiViolation("message has v=$v, expected ${WasmPolicy.ABI_VERSION}")
        return obj
    }

    companion object {
        const val PAGE_BYTES = 65536L
        const val PREFIX_BYTES = 4
    }
}
