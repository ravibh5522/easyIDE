package dev.easyide.extwasm.devicetest

import android.os.Looper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylibso.chicory.wasm.Parser
import dev.easyide.extwasm.ActivationContext
import dev.easyide.extwasm.EnvInfo
import dev.easyide.extwasm.ErrorCode
import dev.easyide.extwasm.HostResult
import dev.easyide.extwasm.WasmHost
import dev.easyide.extwasm.WasmSetting
import dev.easyide.extwasm.binary.Meter
import dev.easyide.extwasm.host.Cap
import dev.easyide.extwasm.host.WasmExtension
import dev.easyide.extwasm.load.WasmModuleLoader
import dev.easyide.extwasm.testing.EMPTY
import dev.easyide.extwasm.testing.FakePorts
import dev.easyide.extwasm.testing.MapSettings
import dev.easyide.extwasm.testing.WasmFixtures
import dev.easyide.extwasm.testing.proxyArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The WasmHost suite on ART (test-plan "WASM host", layer A): the real host, metering pass
 * and limits with the `proxy.wat` guest, plus load-time numbers for the ADR. Tag [TAG].
 */
@RunWith(AndroidJUnit4::class)
class WasmHostDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation()
    private val fixtures = WasmFixtures { name -> ctx.context.assets.open(name).use { it.readBytes() } }
    private val dir: File = File(ctx.targetContext.cacheDir, "wasm-host-test").apply { deleteRecursively(); mkdirs() }
    private val fake = FakePorts()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hosts = mutableListOf<WasmHost>()
    private val activation = ActivationContext("0.1.0", EMPTY, EnvInfo("env", "debian", "arm64"))

    @After fun tearDown() {
        hosts.forEach { it.close() }
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun host(settings: Map<String, Any> = emptyMap()) = WasmHost(
        WasmModuleLoader(File(dir, "cache-${hosts.size}")), fake.ports, MapSettings(settings),
        crashes = { _, _ -> }, scope = scope,
    ).also { hosts += it }

    private fun ext(caps: List<String> = emptyList(), name: String = "proxy"): WasmExtension =
        fixtures.extension(File(dir, "ext-${System.nanoTime()}").apply { mkdirs() }, name = name, caps = caps)

    private fun WasmHost.cmd(e: WasmExtension, c: String, args: JsonArray = JsonArray(emptyList())) =
        runBlocking { executeCommand(e.id, c, args, EMPTY) }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000.0

    @Test fun activationAndCommandLatencyOnArt() {
        val h = host()
        val e = ext(listOf(Cap.FS_READ))
        var t0 = System.nanoTime()
        assertEquals(HostResult.Ok(null), runBlocking { h.activate(e, activation) })
        val cold = ms(t0)
        runBlocking { h.deactivate(e.id) }
        t0 = System.nanoTime()
        runBlocking { h.activate(e, activation) }
        val warm = ms(t0)
        repeat(50) { h.cmd(e, "test.proxy", proxyArgs("editor.getText")) }
        t0 = System.nanoTime()
        val n = 200
        repeat(n) {
            val r = h.cmd(e, "test.proxy", proxyArgs("editor.getText")) as HostResult.Ok
            assertEquals("one two three", r.result!!.jsonObject["result"]!!.jsonPrimitive.content)
        }
        val perCommand = ms(t0) / n
        // The proxy guest finds its scenario by interpreted substring search, so most of that
        // time is guest work; the minimal guest isolates the host's own per-call overhead.
        val m = ext(name = "minimal")
        runBlocking { h.activate(m, activation) }
        repeat(200) { h.cmd(m, "any") }
        t0 = System.nanoTime()
        repeat(n) { assertEquals(HostResult.Ok(null), h.cmd(m, "any")) }
        val overhead = ms(t0) / n
        Log.i(
            TAG,
            "activate cold=%.1f ms warm=%.1f ms; proxy command with one host_call=%.3f ms; host overhead per command=%.3f ms"
                .format(cold, warm, perCommand, overhead),
        )
    }

    @Test fun meteringPassCostOnARealModule() {
        val large = fixtures.bytes("large_real")
        Meter.instrument(large)
        val t0 = System.nanoTime()
        val m = Meter.instrument(large)
        val meter = ms(t0)
        val t1 = System.nanoTime()
        Parser.parse(m.bytes)
        Log.i(TAG, "meter large_real(${large.size} B -> ${m.bytes.size} B)=%.0f ms, parse metered=%.0f ms".format(meter, ms(t1)))
    }

    @Test fun fuelExhaustionAndTimeoutStopARunawayGuest() {
        val fuel = host(mapOf(WasmSetting.FUEL_PER_CALL.key to 1_000_000))
        val e = ext()
        runBlocking { fuel.activate(e, activation) }
        var t0 = System.nanoTime()
        assertEquals(ErrorCode.E_LIMIT, (fuel.cmd(e, "test.spin") as HostResult.Err).code)
        val fuelMs = ms(t0)
        val timed = host(mapOf(WasmSetting.CALL_TIMEOUT_MS.key to 200, WasmSetting.FUEL_PER_CALL.key to Int.MAX_VALUE))
        val e2 = ext()
        runBlocking { timed.activate(e2, activation) }
        t0 = System.nanoTime()
        assertEquals(ErrorCode.E_TIMEOUT, (timed.cmd(e2, "test.spin") as HostResult.Err).code)
        val timeoutMs = ms(t0)
        assertTrue("timeout took $timeoutMs ms", timeoutMs < 1_000)
        Log.i(TAG, "1M fuel exhausted after %.0f ms; 200 ms timeout fired after %.0f ms".format(fuelMs, timeoutMs))
    }

    @Test fun memoryCapHostCallCapAndRecursionAreEnforced() {
        val h = host(mapOf(WasmSetting.MAX_MEMORY_MB.key to 2, WasmSetting.MAX_HOST_CALLS_PER_CALL.key to 10))
        val e = ext()
        runBlocking { h.activate(e, activation) }
        assertEquals("-1", ((h.cmd(e, "test.grow") as HostResult.Ok).result as JsonPrimitive).content)
        assertEquals(ErrorCode.E_LIMIT, (h.cmd(e, "test.storm") as HostResult.Err).code)
        assertEquals(ErrorCode.E_LIMIT, (h.cmd(e, "test.recurse") as HostResult.Err).code)
    }

    @Test fun guestCodeAndHostFunctionsNeverRunOnMain() {
        val h = host()
        val e = ext()
        runBlocking { h.activate(e, activation) }
        var onMain = true
        var thread = ""
        fake.onExecute = {
            onMain = Looper.myLooper() == Looper.getMainLooper()
            thread = Thread.currentThread().name
            null
        }
        h.cmd(e, "test.proxy", proxyArgs("commands.execute", buildJsonObject { put("command", "other.cmd") }))
        assertFalse(onMain)
        assertTrue(thread, thread.startsWith("wasm-"))
    }

    @Test fun reentryIntoTheBusyInstanceIsRefused() {
        val h = host()
        val e = ext()
        runBlocking { h.activate(e, activation) }
        fake.onExecute = { JsonPrimitive(h.executeCommand(e.id, "test.noReply", JsonArray(emptyList()), EMPTY).toString()) }
        val r = h.cmd(e, "test.proxy", proxyArgs("commands.execute", buildJsonObject { put("command", "other.cmd") })) as HostResult.Ok
        assertTrue(r.toString(), r.result!!.jsonObject["result"]!!.jsonPrimitive.content.contains("E_UNAVAILABLE"))
    }

    private companion object {
        const val TAG = "WasmHostArt"
    }
}
