package dev.easyide.extwasm.devicetest

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.runtime.ChicoryInterruptedException
import com.dylibso.chicory.runtime.GlobalInstance
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.TrapException
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.MemoryLimits
import com.dylibso.chicory.wasm.types.MutabilityType
import com.dylibso.chicory.wasm.types.ValType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Decision 0014 spike: answers each "to verify" row of wasm-host.md sec 2.1 against Chicory's
 * interpreter on ART, using Chicory directly (no easyIDE code) so a failure here is a runtime
 * fact, not a host bug. Numbers go to logcat tag [TAG]; the assertions are the pass/fail
 * conditions recorded in the ADR.
 */
@RunWith(AndroidJUnit4::class)
class ChicoryOnArtSpikeTest {

    private fun asset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    private val spikeBytes by lazy { asset("spike.wasm") }
    private val spike: WasmModule by lazy { Parser.parse(spikeBytes) }

    /** host_call that echoes the request back into a buffer it obtains from guest `alloc`. */
    private val echoHost = HostFunction(
        "easyide", "host_call",
        FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
    ) { inst, args ->
        val req = inst.memory().readBytes(args[0].toInt(), args[1].toInt())
        val ptr = inst.export("alloc").apply(4L + req.size)[0].toInt()
        inst.memory().writeI32(ptr, req.size)
        inst.memory().write(ptr + 4, req)
        longArrayOf(ptr.toLong())
    }

    private fun instance(
        limits: MemoryLimits? = null,
        volatileGlobals: Boolean = false,
    ): Instance {
        val b = Instance.builder(spike)
            .withImportValues(ImportValues.builder().addFunction(echoHost).build())
        if (limits != null) b.withMemoryLimits(limits)
        if (volatileGlobals) {
            b.withGlobalFactory { lo, hi, type, mut -> VolatileGlobal(lo, hi, type, mut) }
        }
        return b.build()
    }

    private fun medianNanos(runs: Int, block: () -> Unit): Long {
        val samples = LongArray(runs) {
            val t = System.nanoTime(); block(); System.nanoTime() - t
        }
        samples.sort()
        return samples[runs / 2]
    }

    @Test
    fun loadCompileAndCallLatency() {
        repeat(20) { Parser.parse(spikeBytes) }
        val parseSmall = medianNanos(50) { Parser.parse(spikeBytes) }
        val large = asset("large_real.wasm")
        val parseLarge = medianNanos(5) { Parser.parse(large) }
        repeat(20) { instance() }
        val inst = medianNanos(50) { instance() }
        val i = instance()
        val abi = i.export("ext_abi_version")
        repeat(20_000) { abi.apply() }
        val perCall = medianNanos(21) { repeat(10_000) { abi.apply() } } / 10_000
        val echo = i.export("echo")
        val msg = """{"v":1,"id":7,"fn":"editor.getText","args":{"range":null}}""".toByteArray()
        val req = i.export("alloc").apply(msg.size.toLong())[0].toInt()
        i.memory().write(req, msg)
        repeat(2_000) { echo.apply(req.toLong(), msg.size.toLong()) }
        val perHostCall = medianNanos(21) {
            repeat(1_000) { echo.apply(req.toLong(), msg.size.toLong()) }
        } / 1_000
        Log.i(
            TAG,
            "parse small(${spikeBytes.size} B)=${parseSmall / 1000} us; " +
                "parse large_real(${large.size} B)=${parseLarge / 1_000_000} ms; " +
                "instantiate=${inst / 1000} us; export call=$perCall ns; " +
                "host_call round trip with re-entrant alloc=$perHostCall ns",
        )
    }

    @Test
    fun hostFunctionCanCallBackIntoGuestAlloc() {
        val i = instance()
        val msg = """{"v":1,"id":1,"fn":"host.info","args":{}}""".toByteArray()
        val req = i.export("alloc").apply(msg.size.toLong())[0].toInt()
        i.memory().write(req, msg)
        val resp = i.export("echo").apply(req.toLong(), msg.size.toLong())[0].toInt()
        val len = i.memory().readInt(resp)
        assertEquals(msg.size, len)
        assertEquals(String(msg), String(i.memory().readBytes(resp + 4, len)))
        assertTrue("host buffer must come from the guest heap, after the request", resp > req)
    }

    @Test
    fun interpreterThroughputAndMeteringOverhead() {
        val i = instance()
        val n = 1_000_000L
        val count = i.export("count")
        val metered = i.export("count_metered")
        i.global(FUEL_GLOBAL).setValue(Long.MAX_VALUE)
        count.apply(200_000L); metered.apply(200_000L)
        val raw = medianNanos(5) { assertEquals(n, count.apply(n)[0]) }
        val met = medianNanos(5) {
            i.global(FUEL_GLOBAL).setValue(Long.MAX_VALUE)
            assertEquals(n, metered.apply(n)[0])
        }
        val rawIps = RAW_BODY * n * 1_000_000_000L / raw
        val metIps = METERED_BODY * n * 1_000_000_000L / met
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val debuggable = ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        Log.i(
            TAG,
            "debuggable=$debuggable vm=${System.getProperty("java.vm.version")} " +
                "throughput raw=${rawIps / 1_000_000} M instr/s (${raw / 1_000_000} ms for $n iters); " +
                "metered=${metIps / 1_000_000} M instr/s (${met / 1_000_000} ms); " +
                "wall overhead=${(met - raw) * 100 / raw}%",
        )
        val v = instance(volatileGlobals = true)
        v.global(FUEL_GLOBAL).setValue(Long.MAX_VALUE)
        v.export("count_metered").apply(200_000L)
        val vol = medianNanos(5) {
            v.global(FUEL_GLOBAL).setValue(Long.MAX_VALUE)
            v.export("count_metered").apply(n)
        }
        Log.i(TAG, "metered with volatile globals=${vol / 1_000_000} ms (plain ${met / 1_000_000} ms)")
    }

    @Test
    fun fuelExhaustionTrapsAtCheckpoint() {
        val i = instance()
        i.global(FUEL_GLOBAL).setValue(700L)
        try {
            i.export("count_metered").apply(1_000_000_000L)
            fail("expected trap")
        } catch (e: TrapException) {
            assertTrue(i.global(FUEL_GLOBAL).value < 0)
        }
    }

    @Test
    fun memoryLimitsCapGrowBelowModuleMax() {
        val i = instance(MemoryLimits(1, 16))
        val grow = i.export("grow")
        assertEquals(1L, grow.apply(15L)[0])
        assertEquals(-1L, grow.apply(1L)[0])
        assertEquals(16, i.memory().pages())
    }

    @Test
    fun threadInterruptStopsBrLoop() {
        val (err, ms) = runAndInterrupt(instance(), "spin_br")
        assertTrue("got $err", err is ChicoryInterruptedException)
        Log.i(TAG, "Thread.interrupt stopped a br loop after $ms ms")
    }

    /** Chicory polls the interrupt flag on call and br only: a br_if loop runs to its end. */
    @Test
    fun threadInterruptDoesNotStopBrIfLoop() {
        val i = instance()
        val n = 3_000_000L
        val started = CountDownLatch(1)
        val t0 = AtomicReference<Long>()
        val result = AtomicReference<Any>()
        val th = Thread {
            started.countDown(); t0.set(System.nanoTime())
            result.set(runCatching { i.export("count").apply(n)[0] }.fold({ it }, { it }))
        }
        th.start(); started.await(); Thread.sleep(INTERRUPT_AFTER_MS)
        th.interrupt(); th.join()
        val ms = (System.nanoTime() - t0.get()) / 1_000_000
        Log.i(TAG, "br_if loop after interrupt at ${INTERRUPT_AFTER_MS} ms: result=${result.get()} after $ms ms")
        assertEquals(n, result.get())
    }

    @Test
    fun fuelWriteFromAnotherThreadStopsMeteredLoopPlainGlobal() = crossThreadFuelWrite(false)

    @Test
    fun fuelWriteFromAnotherThreadStopsMeteredLoopVolatileGlobal() = crossThreadFuelWrite(true)

    private fun crossThreadFuelWrite(volatileGlobals: Boolean) {
        val i = instance(volatileGlobals = volatileGlobals)
        i.global(FUEL_GLOBAL).setValue(Long.MAX_VALUE)
        val done = CountDownLatch(1)
        val err = AtomicReference<Throwable>()
        val th = Thread {
            err.set(runCatching { i.export("spin_metered").apply() }.exceptionOrNull()); done.countDown()
        }
        th.start(); Thread.sleep(INTERRUPT_AFTER_MS)
        val t0 = System.nanoTime()
        // Rewritten every tick like the watchdog does: the guest's read-modify-write can
        // overwrite a single store, so one write is not enough by construction.
        while (!done.await(WATCHDOG_TICK_MS, TimeUnit.MILLISECONDS)) {
            i.global(FUEL_GLOBAL).setValue(-1L)
            if (System.nanoTime() - t0 > JOIN_MS * 1_000_000) fail("guest did not observe the fuel write")
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("got ${err.get()}", err.get() is TrapException)
        Log.i(TAG, "cross-thread fuel write (volatile=$volatileGlobals) stopped the loop after $ms ms")
    }

    /**
     * Each wasm call costs several JVM frames in the interpreter, so guest recursion depth is
     * bounded by the worker's JVM stack; overflow must surface as a ChicoryException, not kill
     * the process. Logs the deepest successful depth per stack size.
     */
    @Test
    fun deepRecursionFailsAsChicoryException() {
        for (stack in listOf(0L, 1L shl 20, WORKER_STACK_BYTES)) {
            val deepest = AtomicReference(0L)
            val err = AtomicReference<Throwable>()
            val th = Thread(null, {
                val i = instance()
                for (depth in RECURSION_STEPS) {
                    val r = runCatching { i.export("recurse").apply(depth)[0] }
                    if (r.isFailure) { err.set(r.exceptionOrNull()); break }
                    deepest.set(r.getOrThrow())
                }
            }, "wasm-spike", stack)
            th.start(); th.join()
            Log.i(TAG, "stack=${stack shr 10} KiB (0 = default): deepest ok=${deepest.get()}, then ${err.get()}")
            assertTrue("got ${err.get()}", err.get() is ChicoryException)
        }
    }

    private fun runAndInterrupt(i: Instance, fn: String): Pair<Throwable?, Long> {
        val err = AtomicReference<Throwable>()
        val th = Thread { err.set(runCatching { i.export(fn).apply() }.exceptionOrNull()) }
        th.start(); Thread.sleep(INTERRUPT_AFTER_MS)
        val t0 = System.nanoTime()
        th.interrupt(); th.join(JOIN_MS)
        if (th.isAlive) fail("$fn still running after interrupt")
        return err.get() to (System.nanoTime() - t0) / 1_000_000
    }

    /** Global whose value is volatile, so a write from the watchdog thread is guaranteed visible. */
    private class VolatileGlobal(lo: Long, hi: Long, type: ValType, mut: MutabilityType) :
        GlobalInstance(lo, hi, type, mut) {
        @Volatile private var v = lo
        override fun getValue() = v
        override fun getValueLow() = v
        override fun setValue(value: Long) { v = value }
        override fun setValueLow(value: Long) { v = value }
        override fun setValue(value: com.dylibso.chicory.wasm.types.Value) { v = value.raw() }
    }

    private companion object {
        const val TAG = "WasmSpike"
        const val FUEL_GLOBAL = 1
        const val RAW_BODY = 8L
        const val METERED_BODY = 16L
        const val INTERRUPT_AFTER_MS = 100L
        const val WATCHDOG_TICK_MS = 10L
        const val JOIN_MS = 10_000L
        const val WORKER_STACK_BYTES = 8L shl 20
        val RECURSION_STEPS = listOf(500L, 1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 50_000L, 100_000L, 1_000_000L, 100_000_000L)
    }
}
