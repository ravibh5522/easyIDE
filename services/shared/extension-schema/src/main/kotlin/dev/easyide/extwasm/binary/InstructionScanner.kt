package dev.easyide.extwasm.binary

/**
 * Where the metering pass must insert a fuel checkpoint and how much it charges.
 * [at] is an absolute offset into the module bytes.
 */
internal data class Checkpoint(val at: Int, val cost: Long)

/** Result of scanning one function body: where its locals end and its checkpoints. */
internal class FunctionPlan(val instructionsStart: Int, val checkpoints: List<Checkpoint>)

/**
 * Decodes one code-section function body just far enough to find loop headers and count
 * instructions (wasm-host.md sec 4).
 *
 * Cost model, and why it is sound: every instruction is charged to its *region*, the innermost
 * enclosing `loop` or, outside loops, the function entry. A region's checkpoint sits at its
 * head (function entry / first instruction inside the loop) and charges the whole region.
 * An instruction can run again only after a backward branch, and wasm branches backwards
 * only to a `loop` header, which re-runs that loop's checkpoint (and entering a nested loop
 * runs the nested one). So between two passes of a region's checkpoint each of its
 * instructions runs at most once: fuel charged >= instructions executed. Both arms of an
 * `if` are charged; over-counting is allowed, exactness is not a goal.
 *
 * Supported feature set: MVP, sign-extension, non-trapping float-to-int, bulk memory,
 * reference types, multi-value and tail calls - what current Rust (`wasm32-unknown-unknown`)
 * and AssemblyScript emit. SIMD, threads/atomics (Chicory needs API 33 VarHandles for them),
 * exception handling and GC are refused with a named reason.
 */
internal object InstructionScanner {

    fun scan(bytes: ByteArray, body: IntRange, functionIndex: Int): FunctionPlan {
        val r = WasmReader(bytes, body.first, body.last + 1)
        skipLocals(r, functionIndex)
        val start = r.pos
        val costs = ArrayList<Long>().apply { add(0L) }
        val heads = ArrayList<Int>().apply { add(start) }
        // Control stack of region indices; the function body itself is the outermost frame.
        val frames = ArrayList<Int>().apply { add(0) }
        while (frames.isNotEmpty()) {
            val region = frames.last()
            costs[region] = costs[region] + 1
            when (val op = r.byte()) {
                OP_BLOCK, OP_IF -> { skipBlockType(r); frames += region }
                OP_LOOP -> {
                    skipBlockType(r)
                    costs += 0L; heads += r.pos
                    frames += costs.lastIndex
                }
                OP_END -> frames.removeAt(frames.lastIndex)
                else -> skipImmediates(r, op, functionIndex)
            }
        }
        if (!r.atEnd) r.fail("code after the final end of function $functionIndex")
        return FunctionPlan(start, heads.indices.map { Checkpoint(heads[it], costs[it]) })
    }

    private fun skipLocals(r: WasmReader, functionIndex: Int) {
        repeat(r.u32()) {
            r.u32()
            skipValType(r, functionIndex)
        }
    }

    private fun skipValType(r: WasmReader, functionIndex: Int) {
        when (val t = r.byte()) {
            T_I32, T_I64, T_F32, T_F64, T_FUNCREF, T_EXTERNREF -> Unit
            T_V128 -> unsupported(r, "SIMD (v128)", functionIndex)
            else -> r.fail("unsupported value type 0x%02x in function $functionIndex".format(t))
        }
    }

    private fun skipBlockType(r: WasmReader) {
        when (r.peek()) {
            BLOCK_EMPTY, T_I32, T_I64, T_F32, T_F64, T_FUNCREF, T_EXTERNREF -> r.byte()
            T_V128 -> r.fail("SIMD (v128) block type is not supported")
            else -> r.skipSigned(S33_BITS)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun skipImmediates(r: WasmReader, op: Int, functionIndex: Int) {
        when (op) {
            OP_UNREACHABLE, OP_NOP, OP_ELSE, OP_RETURN, OP_DROP, OP_SELECT, OP_REF_IS_NULL -> Unit
            OP_BR, OP_BR_IF, OP_CALL, OP_RETURN_CALL, OP_REF_FUNC, OP_TABLE_GET, OP_TABLE_SET -> r.u32()
            in OP_LOCAL_GET..OP_GLOBAL_SET -> r.u32()
            OP_BR_TABLE -> { repeat(r.u32()) { r.u32() }; r.u32() }
            OP_CALL_INDIRECT, OP_RETURN_CALL_INDIRECT -> { r.u32(); r.u32() }
            OP_SELECT_TYPED -> repeat(r.u32()) { skipValType(r, functionIndex) }
            in OP_FIRST_MEMORY..OP_LAST_MEMORY -> skipMemArg(r)
            OP_MEMORY_SIZE, OP_MEMORY_GROW -> r.u32()
            OP_I32_CONST -> r.skipSigned(I32_BITS)
            OP_I64_CONST -> r.skipSigned(I64_BITS)
            OP_F32_CONST -> r.skip(F32_BYTES)
            OP_F64_CONST -> r.skip(F64_BYTES)
            in OP_FIRST_NUMERIC..OP_LAST_NUMERIC -> Unit
            OP_REF_NULL -> skipHeapType(r)
            PREFIX_MISC -> skipMisc(r, functionIndex)
            PREFIX_SIMD -> unsupported(r, "SIMD", functionIndex)
            PREFIX_THREADS -> unsupported(r, "threads/atomics", functionIndex)
            PREFIX_GC -> unsupported(r, "GC", functionIndex)
            in EXCEPTION_OPS -> unsupported(r, "exception handling", functionIndex)
            else -> r.fail("unsupported opcode 0x%02x in function $functionIndex".format(op))
        }
    }

    private fun skipMemArg(r: WasmReader) {
        val align = r.u32()
        if (align and MEMARG_HAS_MEMIDX != 0) r.u32()
        r.u32()
    }

    private fun skipHeapType(r: WasmReader) {
        when (r.peek()) {
            T_FUNCREF, T_EXTERNREF -> r.byte()
            else -> r.skipSigned(S33_BITS)
        }
    }

    private fun skipMisc(r: WasmReader, functionIndex: Int) {
        when (val sub = r.u32()) {
            in MISC_TRUNC_SAT -> Unit
            MISC_MEMORY_INIT, MISC_TABLE_INIT, MISC_MEMORY_COPY, MISC_TABLE_COPY -> { r.u32(); r.u32() }
            MISC_DATA_DROP, MISC_MEMORY_FILL, MISC_ELEM_DROP,
            MISC_TABLE_GROW, MISC_TABLE_SIZE, MISC_TABLE_FILL -> r.u32()
            else -> r.fail("unsupported 0xfc sub-opcode $sub in function $functionIndex")
        }
    }

    private fun unsupported(r: WasmReader, feature: String, functionIndex: Int): Nothing =
        r.fail("$feature is not supported by the easyIDE WASM host (function $functionIndex)")

    const val T_I32 = 0x7F
    const val T_I64 = 0x7E
    const val T_F32 = 0x7D
    const val T_F64 = 0x7C
    const val T_V128 = 0x7B
    const val T_FUNCREF = 0x70
    const val T_EXTERNREF = 0x6F
    const val BLOCK_EMPTY = 0x40
    const val S33_BITS = 33
    const val I32_BITS = 32
    const val I64_BITS = 64
    const val F32_BYTES = 4
    const val F64_BYTES = 8
    const val MEMARG_HAS_MEMIDX = 0x40

    const val OP_UNREACHABLE = 0x00
    const val OP_NOP = 0x01
    const val OP_BLOCK = 0x02
    const val OP_LOOP = 0x03
    const val OP_IF = 0x04
    const val OP_ELSE = 0x05
    const val OP_END = 0x0B
    const val OP_BR = 0x0C
    const val OP_BR_IF = 0x0D
    const val OP_BR_TABLE = 0x0E
    const val OP_RETURN = 0x0F
    const val OP_CALL = 0x10
    const val OP_CALL_INDIRECT = 0x11
    const val OP_RETURN_CALL = 0x12
    const val OP_RETURN_CALL_INDIRECT = 0x13
    const val OP_DROP = 0x1A
    const val OP_SELECT = 0x1B
    const val OP_SELECT_TYPED = 0x1C
    const val OP_LOCAL_GET = 0x20
    const val OP_GLOBAL_GET = 0x23
    const val OP_GLOBAL_SET = 0x24
    const val OP_TABLE_GET = 0x25
    const val OP_TABLE_SET = 0x26
    const val OP_FIRST_MEMORY = 0x28
    const val OP_LAST_MEMORY = 0x3E
    const val OP_MEMORY_SIZE = 0x3F
    const val OP_MEMORY_GROW = 0x40
    const val OP_I32_CONST = 0x41
    const val OP_I64_CONST = 0x42
    const val OP_F32_CONST = 0x43
    const val OP_F64_CONST = 0x44
    const val OP_FIRST_NUMERIC = 0x45
    const val OP_I64_LT_S = 0x53
    const val OP_I64_SUB = 0x7D
    const val OP_LAST_NUMERIC = 0xC4
    const val OP_REF_NULL = 0xD0
    const val OP_REF_IS_NULL = 0xD1
    const val OP_REF_FUNC = 0xD2
    const val PREFIX_GC = 0xFB
    const val PREFIX_MISC = 0xFC
    const val PREFIX_SIMD = 0xFD
    const val PREFIX_THREADS = 0xFE

    /** try, catch, throw, rethrow, throw_ref, delegate, catch_all, try_table. */
    val EXCEPTION_OPS = setOf(0x06, 0x07, 0x08, 0x09, 0x0A, 0x18, 0x19, 0x1F)
    val MISC_TRUNC_SAT = 0..7
    const val MISC_MEMORY_INIT = 8
    const val MISC_DATA_DROP = 9
    const val MISC_MEMORY_COPY = 10
    const val MISC_MEMORY_FILL = 11
    const val MISC_TABLE_INIT = 12
    const val MISC_ELEM_DROP = 13
    const val MISC_TABLE_COPY = 14
    const val MISC_TABLE_GROW = 15
    const val MISC_TABLE_SIZE = 16
    const val MISC_TABLE_FILL = 17
}
