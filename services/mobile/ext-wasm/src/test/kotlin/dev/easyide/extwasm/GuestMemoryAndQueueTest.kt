package dev.easyide.extwasm

import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import dev.easyide.extwasm.load.AbiV1
import dev.easyide.extwasm.runtime.AbiViolation
import dev.easyide.extwasm.runtime.CrashWindow
import dev.easyide.extwasm.runtime.EventQueue
import dev.easyide.extwasm.runtime.GuestMemory
import dev.easyide.extwasm.testing.MapSettings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestMemoryAndQueueTest {
    private val limit = 64
    private val instance = Instance.builder(Parser.parse(Fixtures.bytes("proxy")))
        .withImportValues(ImportValues.builder().addFunction(HostFunction(AbiV1.HOST_MODULE, AbiV1.HOST_CALL, AbiV1.HOST_CALL_TYPE) { _, _ -> longArrayOf(0) }).build())
        .build()
    private val mem = GuestMemory(instance, limit)
    private val size = instance.memory().pages() * 65536

    private fun put(at: Int, text: String) = instance.memory().write(at, text.toByteArray())

    @Test fun readsAValidRequest() {
        put(100, """{"v":1,"id":1,"fn":"x"}""")
        assertEquals("x", mem.readRequest(100, 23)["fn"]!!.jsonPrimitive.content)
    }

    @Test fun rejectsOutOfBoundsNegativeAndOversizedBuffers() {
        assertThrows(AbiViolation::class.java) { mem.readRequest(size - 4, 10) }
        assertThrows(AbiViolation::class.java) { mem.readRequest(-8, 4) }
        assertThrows(AbiViolation::class.java) { mem.readRequest(100, -1) }
        assertThrows(AbiViolation::class.java) { mem.readRequest(100, limit + 1) }
    }

    @Test fun messageExactlyAtTheLimitIsAcceptedOneOverIsNot() {
        val base = """{"v":1,"pad":""}"""
        val atLimit = base.replace("\"\"", "\"" + "a".repeat(limit - base.length) + "\"")
        put(200, atLimit)
        assertEquals(limit, atLimit.length)
        mem.readRequest(200, limit)
        assertThrows(AbiViolation::class.java) { mem.readRequest(200, limit + 1) }
    }

    @Test fun rejectsNonUtf8NonJsonNonObjectAndWrongVersion() {
        instance.memory().write(300, byteArrayOf(0xC3.toByte(), 0x28))
        assertThrows(AbiViolation::class.java) { mem.readRequest(300, 2) }
        put(300, "{nope")
        assertThrows(AbiViolation::class.java) { mem.readRequest(300, 5) }
        put(300, "[1,2]")
        assertThrows(AbiViolation::class.java) { mem.readRequest(300, 5) }
        put(300, """{"v":2}""")
        assertThrows(AbiViolation::class.java) { mem.readRequest(300, 7) }
    }

    @Test fun lengthPrefixAtTheMemoryEdgeAndOverflowAreRejected() {
        assertThrows(AbiViolation::class.java) { mem.takePrefixed(size - 2) }
        instance.memory().writeI32(400, -1)
        assertThrows(AbiViolation::class.java) { mem.takePrefixed(400) }
        instance.memory().writeI32(size - 8, 32)
        assertThrows(AbiViolation::class.java) { mem.takePrefixed(size - 8) }
    }

    @Test fun prefixedRoundTrip() {
        val p = mem.writePrefixed("""{"v":1,"ok":true}""".toByteArray())
        assertEquals(true, mem.takePrefixed(p)["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun queueCoalescesStateEventsPerPathAndCountsDrops() {
        val q = EventQueue(3)
        q.offer("workspace.didChange", buildJsonObject { put("path", "/a"); put("n", 1) })
        q.offer("workspace.didChange", buildJsonObject { put("path", "/a"); put("n", 2) })
        q.offer("workspace.didChange", buildJsonObject { put("path", "/b"); put("n", 3) })
        q.offer("workspace.didSave", buildJsonObject { put("path", "/a") })
        q.offer("workspace.didSave", buildJsonObject { put("path", "/b") }) // full: drops the oldest save
        val first = q.poll()!!
        assertEquals("2", first["data"]!!.jsonObject["n"]!!.jsonPrimitive.content)
        assertEquals("1", first["events.dropped"]!!.jsonPrimitive.content)
        assertEquals("3", q.poll()!!["data"]!!.jsonObject["n"]!!.jsonPrimitive.content)
        val last = q.poll()!!
        assertEquals("/b", last["data"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertNull(last["events.dropped"])
        assertNull(q.poll())
        assertTrue(q.isEmpty())
    }

    @Test fun crashWindowCountsOnlyWithinTheWindow() {
        val w = CrashWindow(3, 300_000)
        assertFalse(w.record(0)); assertFalse(w.record(100_000)); assertTrue(w.record(299_000))
        val spread = CrashWindow(3, 300_000)
        assertFalse(spread.record(0)); assertFalse(spread.record(150_500)); assertFalse(spread.record(301_000))
    }

    @Test fun limitsFallBackToDefaultsForUnusableValues() {
        val l = WasmLimits.resolve(MapSettings(mapOf(WasmSetting.CALL_TIMEOUT_MS.key to 0, WasmSetting.FUEL_PER_CALL.key to "lots")))
        assertEquals(WasmSetting.CALL_TIMEOUT_MS.default, l.callTimeoutMs)
        assertEquals(WasmSetting.FUEL_PER_CALL.default, l.fuelPerCall)
        assertEquals(4096 * 1024, l.maxMessageBytes)
        assertEquals(1024, l.maxMemoryPages)
        assertTrue(WasmLimits.enabled(MapSettings()))
        assertFalse(WasmLimits.enabled(MapSettings(mapOf(WASM_ENABLED_KEY to false))))
    }
}
