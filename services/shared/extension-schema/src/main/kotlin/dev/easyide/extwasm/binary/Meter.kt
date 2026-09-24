package dev.easyide.extwasm.binary

import dev.easyide.extwasm.binary.InstructionScanner.OP_END
import dev.easyide.extwasm.binary.InstructionScanner.OP_GLOBAL_GET
import dev.easyide.extwasm.binary.InstructionScanner.OP_GLOBAL_SET
import dev.easyide.extwasm.binary.InstructionScanner.OP_I64_CONST
import dev.easyide.extwasm.binary.InstructionScanner.OP_I64_LT_S
import dev.easyide.extwasm.binary.InstructionScanner.OP_I64_SUB
import dev.easyide.extwasm.binary.InstructionScanner.OP_IF
import dev.easyide.extwasm.binary.InstructionScanner.OP_UNREACHABLE
import java.io.ByteArrayOutputStream

/** A metered module and the index of the fuel global the host reads and writes. */
class MeteredModule(val bytes: ByteArray, val fuelGlobalIndex: Int)

/**
 * The metering pass (wasm-host.md sec 4): appends one mutable i64 global (the fuel counter,
 * never exported) and inserts a checkpoint at every function entry and loop header:
 *
 * ```
 * global.get $fuel; i64.const cost; i64.sub; global.set $fuel
 * global.get $fuel; i64.const 0; i64.lt_s; if; unreachable; end
 * ```
 *
 * (The LLD sketch used `global.tee`, which wasm does not have.) Only the global and code
 * sections are re-emitted; every other section is copied byte for byte, and because the
 * global is appended and no import is added, no function, type or global index shifts.
 * The same global is the interruption channel: the watchdog writes -1 into it.
 *
 * The encoder is ours on purpose: Chicory's `WasmWriter` only frames raw sections and uses
 * an API 33 method, and nothing else needs an encoder.
 */
object Meter {
    /** Bump to invalidate every on-disk metered-module cache entry. */
    const val VERSION = 1

    private const val T_I64 = 0x7E
    private const val MUTABLE = 0x01
    private const val BLOCK_EMPTY = 0x40
    private const val IMPORT_KIND_GLOBAL = 0x03
    private const val IMPORT_KIND_FUNC = 0x00
    private const val IMPORT_KIND_TABLE = 0x01
    private const val IMPORT_KIND_MEMORY = 0x02
    private const val IMPORT_KIND_TAG = 0x04
    private const val LIMITS_HAS_MAX = 0x01

    /** Sections that must precede the global section, in binary order (custom ones aside). */
    private val BEFORE_GLOBAL = setOf(
        SectionId.TYPE, SectionId.IMPORT, SectionId.FUNCTION, SectionId.TABLE, SectionId.MEMORY, SectionId.TAG,
    )

    /** @throws dev.easyide.extwasm.ModuleRejectedException on malformed or unsupported code. */
    fun instrument(module: ByteArray): MeteredModule {
        val sections = readSections(module)
        val importedGlobals = sections.firstOrNull { it.id == SectionId.IMPORT }
            ?.let { countImportedGlobals(module, it.payload) } ?: 0
        val globals = sections.firstOrNull { it.id == SectionId.GLOBAL }
        val definedGlobals = globals?.let { WasmReader(module, it.payload.first).u32() } ?: 0
        val fuelIndex = importedGlobals + definedGlobals

        val out = ByteArrayOutputStream(module.size + module.size / 4)
        out.write(WASM_HEADER)
        var globalWritten = false
        for (s in sections) {
            if (!globalWritten && globals == null && s.id != SectionId.CUSTOM && s.id !in BEFORE_GLOBAL) {
                writeSection(out, SectionId.GLOBAL, globalSection(module, null))
                globalWritten = true
            }
            when (s.id) {
                SectionId.GLOBAL -> { writeSection(out, s.id, globalSection(module, s.payload)); globalWritten = true }
                SectionId.CODE -> writeSection(out, s.id, codeSection(module, s.payload, fuelIndex))
                else -> writeSection(out, s.id, module.copyOfRange(s.payload.first, s.payload.last + 1))
            }
        }
        if (!globalWritten) writeSection(out, SectionId.GLOBAL, globalSection(module, null))
        return MeteredModule(out.toByteArray(), fuelIndex)
    }

    private fun writeSection(out: ByteArrayOutputStream, id: Int, payload: ByteArray) {
        out.write(id)
        out.u32(payload.size)
        out.write(payload)
    }

    private fun globalSection(module: ByteArray, existing: IntRange?): ByteArray {
        val out = ByteArrayOutputStream()
        if (existing == null) {
            out.u32(1)
        } else {
            val r = WasmReader(module, existing.first, existing.last + 1)
            out.u32(r.u32() + 1)
            out.bytes(module, r.pos..existing.last)
        }
        out.write(T_I64); out.write(MUTABLE)
        out.write(OP_I64_CONST); out.s64(0); out.write(OP_END)
        return out.toByteArray()
    }

    private fun codeSection(module: ByteArray, payload: IntRange, fuelIndex: Int): ByteArray {
        val r = WasmReader(module, payload.first, payload.last + 1)
        val count = r.u32()
        val out = ByteArrayOutputStream(payload.last - payload.first + 1)
        out.u32(count)
        for (i in 0 until count) {
            val body = r.sized()
            val plan = InstructionScanner.scan(module, body, i)
            val fn = ByteArrayOutputStream(body.last - body.first + 1)
            var copied = body.first
            for (cp in plan.checkpoints.sortedBy { it.at }) {
                fn.bytes(module, copied until cp.at)
                writeCheckpoint(fn, fuelIndex, cp.cost)
                copied = cp.at
            }
            fn.bytes(module, copied..body.last)
            out.u32(fn.size())
            fn.writeTo(out)
        }
        if (!r.atEnd) r.fail("trailing bytes in code section")
        return out.toByteArray()
    }

    private fun writeCheckpoint(out: ByteArrayOutputStream, fuel: Int, cost: Long) {
        out.write(OP_GLOBAL_GET); out.u32(fuel)
        out.write(OP_I64_CONST); out.s64(cost)
        out.write(OP_I64_SUB)
        out.write(OP_GLOBAL_SET); out.u32(fuel)
        out.write(OP_GLOBAL_GET); out.u32(fuel)
        out.write(OP_I64_CONST); out.s64(0)
        out.write(OP_I64_LT_S)
        out.write(OP_IF); out.write(BLOCK_EMPTY)
        out.write(OP_UNREACHABLE)
        out.write(OP_END)
    }

    private fun countImportedGlobals(module: ByteArray, payload: IntRange): Int {
        val r = WasmReader(module, payload.first, payload.last + 1)
        var globals = 0
        repeat(r.u32()) {
            r.sized(); r.sized()
            when (val kind = r.byte()) {
                IMPORT_KIND_FUNC -> r.u32()
                IMPORT_KIND_TABLE -> { r.byte(); skipLimits(r) }
                IMPORT_KIND_MEMORY -> skipLimits(r)
                IMPORT_KIND_GLOBAL -> { r.byte(); r.byte(); globals++ }
                IMPORT_KIND_TAG -> { r.byte(); r.u32() }
                else -> r.fail("unknown import kind $kind")
            }
        }
        return globals
    }

    private fun skipLimits(r: WasmReader) {
        val flags = r.byte()
        r.u32()
        if (flags and LIMITS_HAS_MAX != 0) r.u32()
    }
}
