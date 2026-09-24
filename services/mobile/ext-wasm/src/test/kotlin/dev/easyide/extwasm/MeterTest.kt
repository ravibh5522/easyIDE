package dev.easyide.extwasm

import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.TrapException
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import dev.easyide.extwasm.binary.Meter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MeterTest {
    private val original = Fixtures.bytes("meter_cases")
    private val metered = Meter.instrument(original)

    private fun instance(m: WasmModule) = Instance.builder(m).build()

    @Test fun meteredModuleIsValidAndBehavesTheSame() {
        val a = instance(Parser.parse(original))
        val b = instance(Parser.parse(metered.bytes))
        b.global(metered.fuelGlobalIndex).setValue(Long.MAX_VALUE)
        val cases = listOf(
            "fib" to listOf(0L, 1L, 10L, 50L),
            "nested" to listOf(0L, 1L, 7L, 30L),
            "switch" to listOf(0L, 1L, 2L, 9L),
            "early" to listOf(0L, 5L, 40L),
            "multi" to listOf(1L, 6L, 100L),
            "features" to listOf(3L, 200L),
        )
        for ((fn, inputs) in cases) {
            for (x in inputs) assertEquals("$fn($x)", a.export(fn).apply(x)[0], b.export(fn).apply(x)[0])
        }
        for (which in 0L..1L) assertEquals(a.export("indirect").apply(which, 9L)[0], b.export("indirect").apply(which, 9L)[0])
    }

    @Test fun noIndexShiftsAndTheFuelGlobalIsNewAndHidden() {
        val a = Parser.parse(original)
        val b = Parser.parse(metered.bytes)
        assertEquals(0, a.globalSection().globalCount())
        assertEquals(1, b.globalSection().globalCount())
        assertEquals(0, metered.fuelGlobalIndex)
        assertEquals(a.exportSection().exportCount(), b.exportSection().exportCount())
        for (i in 0 until a.exportSection().exportCount()) {
            val x = a.exportSection().getExport(i)
            val y = b.exportSection().getExport(i)
            assertEquals(x.name(), y.name()); assertEquals(x.index(), y.index()); assertEquals(x.exportType(), y.exportType())
        }
        assertEquals(a.functionSection().functionCount(), b.functionSection().functionCount())
        assertEquals(a.elementSection().elementCount(), b.elementSection().elementCount())
    }

    @Test fun appendsToAnExistingGlobalSection() {
        val proxy = Fixtures.bytes("proxy")
        val before = Parser.parse(proxy).globalSection().globalCount()
        val m = Meter.instrument(proxy)
        assertEquals(before, m.fuelGlobalIndex)
        assertEquals(before + 1, Parser.parse(m.bytes).globalSection().globalCount())
    }

    @Test fun infiniteLoopTrapsWhenFuelRunsOut() {
        val b = instance(Parser.parse(metered.bytes))
        b.global(metered.fuelGlobalIndex).setValue(10_000L)
        assertThrows(TrapException::class.java) { b.export("spin").apply() }
        assertTrue(b.global(metered.fuelGlobalIndex).value < 0)
    }

    @Test fun fuelChargedCoversEveryExecutedInstruction() {
        // nested(n) runs ~6 n^2 instructions; the charge must be at least that (sound, not exact).
        val b = instance(Parser.parse(metered.bytes))
        val start = 1_000_000_000L
        b.global(metered.fuelGlobalIndex).setValue(start)
        b.export("nested").apply(30L)
        val used = start - b.global(metered.fuelGlobalIndex).value
        assertTrue("used $used", used >= 6L * 30 * 30)
    }

    @Test fun realCompilerOutputMetersAndStillValidates() {
        val large = Fixtures.bytes("large_real")
        val m = Meter.instrument(large)
        val parsed = Parser.parse(m.bytes)
        assertEquals(Parser.parse(large).functionSection().functionCount(), parsed.functionSection().functionCount())
    }

    @Test fun unsupportedFeaturesAreRefusedByName() {
        val e = assertThrows(ModuleRejectedException::class.java) { Meter.instrument(Fixtures.bytes("simd")) }
        assertTrue(e.message, e.message!!.contains("SIMD"))
    }

    @Test fun garbageNeverEscapesAsAnythingButARejection() {
        val rnd = Random(20260924)
        assertThrows(ModuleRejectedException::class.java) { Meter.instrument(byteArrayOf(1, 2, 3)) }
        repeat(500) {
            val mutated = original.copyOf()
            repeat(1 + rnd.nextInt(4)) { mutated[8 + rnd.nextInt(mutated.size - 8)] = rnd.nextInt(256).toByte() }
            val truncated = mutated.copyOf(8 + rnd.nextInt(mutated.size - 8))
            for (bytes in listOf(mutated, truncated)) {
                try {
                    Meter.instrument(bytes)
                } catch (e: ModuleRejectedException) {
                    // expected for most mutations
                }
            }
        }
    }
}
