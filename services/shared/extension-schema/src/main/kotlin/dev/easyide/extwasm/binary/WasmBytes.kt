package dev.easyide.extwasm.binary

import dev.easyide.extwasm.ModuleRejectedException
import java.io.ByteArrayOutputStream

/**
 * Cursor over untrusted wasm bytes. Every read is bounds-checked and fails with
 * [ModuleRejectedException], so a truncated or hostile binary is a load error, never an
 * index exception escaping the loader.
 */
internal class WasmReader(val bytes: ByteArray, var pos: Int = 0, val end: Int = bytes.size) {

    val atEnd: Boolean get() = pos >= end

    fun byte(): Int {
        if (pos >= end) fail("unexpected end of data")
        return bytes[pos++].toInt() and BYTE_MASK
    }

    fun peek(): Int {
        if (pos >= end) fail("unexpected end of data")
        return bytes[pos].toInt() and BYTE_MASK
    }

    fun skip(n: Int) {
        if (n < 0 || n > end - pos) fail("length $n past end of data")
        pos += n
    }

    fun u32(): Int {
        var result = 0L
        var shift = 0
        while (true) {
            val b = byte()
            result = result or ((b and LEB_PAYLOAD).toLong() shl shift)
            if (b and LEB_CONTINUE == 0) break
            shift += LEB_BITS
            if (shift >= U32_MAX_SHIFT) fail("u32 LEB128 too long")
        }
        if (result > UINT32_MAX) fail("u32 LEB128 out of range")
        return result.toInt()
    }

    /** Signed LEB128 of up to [bits] bits; only the length matters to the scanner. */
    fun skipSigned(bits: Int) {
        val maxBytes = (bits + LEB_BITS - 1) / LEB_BITS
        var n = 0
        while (byte() and LEB_CONTINUE != 0) {
            if (++n >= maxBytes) fail("signed LEB128 too long")
        }
    }

    /** Reads a u32 length and returns [pos, pos + length) as a range, advancing past it. */
    fun sized(): IntRange {
        val len = u32()
        val start = pos
        skip(len)
        return start until pos
    }

    fun fail(message: String): Nothing = throw ModuleRejectedException("malformed wasm at byte $pos: $message")

    companion object {
        const val BYTE_MASK = 0xFF
        const val LEB_PAYLOAD = 0x7F
        const val LEB_CONTINUE = 0x80
        const val LEB_BITS = 7
        const val U32_MAX_SHIFT = 35
        const val UINT32_MAX = 0xFFFF_FFFFL
    }
}

internal fun ByteArrayOutputStream.u32(value: Int) {
    var x = value.toLong() and WasmReader.UINT32_MAX
    while (true) {
        if (x < WasmReader.LEB_CONTINUE) {
            write(x.toInt()); return
        }
        write(((x and WasmReader.LEB_PAYLOAD.toLong()) or WasmReader.LEB_CONTINUE.toLong()).toInt())
        x = x ushr WasmReader.LEB_BITS
    }
}

internal fun ByteArrayOutputStream.s64(value: Long) {
    var x = value
    while (true) {
        val b = (x and WasmReader.LEB_PAYLOAD.toLong()).toInt()
        x = x shr WasmReader.LEB_BITS
        val signBit = b and SIGN_BIT != 0
        if ((x == 0L && !signBit) || (x == -1L && signBit)) {
            write(b); return
        }
        write(b or WasmReader.LEB_CONTINUE)
    }
}

internal fun ByteArrayOutputStream.bytes(src: ByteArray, range: IntRange) {
    write(src, range.first, range.last - range.first + 1)
}

private const val SIGN_BIT = 0x40

/** One top-level section of a module: id and the byte range of its payload. */
internal data class Section(val id: Int, val payload: IntRange)

internal object SectionId {
    const val CUSTOM = 0
    const val TYPE = 1
    const val IMPORT = 2
    const val FUNCTION = 3
    const val TABLE = 4
    const val MEMORY = 5
    const val GLOBAL = 6
    const val EXPORT = 7
    const val START = 8
    const val ELEMENT = 9
    const val CODE = 10
    const val DATA = 11
    const val DATA_COUNT = 12
    const val TAG = 13
}

internal val WASM_HEADER = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)

/** Splits a module into sections without decoding them. */
internal fun readSections(bytes: ByteArray): List<Section> {
    val r = WasmReader(bytes)
    if (bytes.size < WASM_HEADER.size || !WASM_HEADER.indices.all { bytes[it] == WASM_HEADER[it] }) {
        r.fail("not a wasm 1.0 binary")
    }
    r.pos = WASM_HEADER.size
    val out = ArrayList<Section>()
    while (!r.atEnd) {
        val id = r.byte()
        out += Section(id, r.sized())
    }
    return out
}
