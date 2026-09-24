package dev.easyide.extwasm

import dev.easyide.extwasm.host.Cap
import dev.easyide.extwasm.host.LogLevel
import dev.easyide.extwasm.host.ProviderRegistration
import dev.easyide.extwasm.host.WasmExtension
import dev.easyide.extwasm.load.WasmModuleLoader
import dev.easyide.extwasm.testing.EMPTY
import dev.easyide.extwasm.testing.FakePorts
import dev.easyide.extwasm.testing.MapSettings
import dev.easyide.extwasm.testing.proxyArgs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicLong

class WasmHostTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var fake: FakePorts
    private lateinit var scope: CoroutineScope
    private val now = AtomicLong(1_000_000)
    private val disabled = mutableListOf<String>()
    private var host: WasmHost? = null

    private val context = ActivationContext(
        apiVersion = "0.1.0",
        settings = buildJsonObject { put("acme.enabled", true) },
        env = EnvInfo("env1", "debian", "arm64"),
    )

    @Before fun setUp() {
        fake = FakePorts()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        host?.close()
        scope.cancel()
    }

    private fun host(settings: Map<String, Any> = emptyMap()): WasmHost = WasmHost(
        loader = WasmModuleLoader(tmp.newFolder()),
        ports = fake.ports,
        settings = MapSettings(settings),
        crashes = { id, _ -> disabled += id },
        scope = scope,
        clock = now::get,
    ).also { host = it }

    private fun ext(caps: List<String> = emptyList(), name: String = "proxy"): WasmExtension =
        Fixtures.extension(tmp.newFolder(), name = name, caps = caps)

    private fun WasmHost.cmd(ext: WasmExtension, command: String, args: JsonArray = JsonArray(emptyList())) =
        runBlocking { executeCommand(ext.id, command, args, EMPTY) }

    private fun WasmHost.proxy(ext: WasmExtension, fn: String, args: JsonObject = EMPTY): JsonObject {
        val r = cmd(ext, "test.proxy", proxyArgs(fn, args))
        assertTrue("proxy call failed: $r", r is HostResult.Ok)
        return (r as HostResult.Ok).result!!.jsonObject
    }

    private fun activate(h: WasmHost, e: WasmExtension, ctx: ActivationContext = context) =
        runBlocking { h.activate(e, ctx) }

    @Test fun activationSendsTheSpecifiedMessageAndRunsGuestRegistrations() {
        val h = host()
        val e = ext(listOf(Cap.FS_READ))
        assertEquals(HostResult.Ok(null), activate(h, e))
        assertEquals(InstanceState.Active, h.state(e.id).value)
        val msg = fake.stored(e.id, "activation")!!.jsonObject
        assertEquals("activate", msg["type"]!!.jsonPrimitive.content)
        assertEquals(e.id, msg["extensionId"]!!.jsonPrimitive.content)
        assertEquals("1.0.0", msg["version"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", msg["apiVersion"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive(Cap.FS_READ))), msg["capabilities"])
        assertEquals("arm64", msg["env"]!!.jsonObject["arch"]!!.jsonPrimitive.content)
        assertEquals(listOf(e.id to "test.proxy"), fake.registeredCommands)
        assertEquals(listOf(ProviderRegistration(e.id, "completion", listOf("python"))), h.providers.value)
    }

    @Test fun commandRoundTripsThroughHostFunctionsAndFreesEveryBuffer() {
        val h = host()
        val e = ext(listOf(Cap.FS_READ))
        activate(h, e)
        val reply = h.proxy(e, "editor.getText")
        assertEquals(true, reply["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("one two three", reply["result"]!!.jsonPrimitive.content)
        assertEquals("7", reply["id"]!!.jsonPrimitive.content)
        // Activation: its input, 4 host_call replies and (by the host) its reply = 6. The proxy
        // command: its input, the host_call reply and (by the host) its reply = 3.
        val frees = (h.cmd(e, "test.frees") as HostResult.Ok).result!!.jsonPrimitive.content.toInt()
        assertEquals(9, frees)
    }

    @Test fun deniedCapabilityAnswersECapabilityLogsOnceAndKeepsTheInstance() {
        val h = host()
        val e = ext()
        activate(h, e)
        repeat(2) {
            val reply = h.proxy(e, "editor.getText")
            assertEquals("E_CAPABILITY", reply["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
        assertEquals(1, fake.logs.count { it.second == LogLevel.WARN && it.third.startsWith("denied editor.getText") })
        assertEquals(InstanceState.Active, h.state(e.id).value)
    }

    @Test fun unknownFunctionIsENotFound() {
        val h = host()
        val e = ext()
        activate(h, e)
        assertEquals("E_NOT_FOUND", h.proxy(e, "nope.nothing")["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test fun malformedAndOutOfBoundsRequestsAnswerEArgsAndTheHostSurvives() {
        val h = host()
        val e = ext()
        activate(h, e)
        for (c in listOf("test.badRequest", "test.oobRequest")) {
            val r = h.cmd(e, c) as HostResult.Ok
            assertEquals("E_ARGS", r.result!!.jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
        assertEquals(InstanceState.Active, h.state(e.id).value)
    }

    @Test fun fuelExhaustionIsELimitAndDiscardsTheInstance() {
        val h = host(mapOf(WasmSetting.FUEL_PER_CALL.key to 100_000))
        val e = ext()
        activate(h, e)
        val r = h.cmd(e, "test.spin") as HostResult.Err
        assertEquals(ErrorCode.E_LIMIT, r.code)
        assertTrue(r.message, r.message.contains("fuel"))
        assertEquals(InstanceState.Unloaded, h.state(e.id).value)
        // The next command re-instantiates.
        assertTrue(h.cmd(e, "test.noReply") is HostResult.Ok)
        assertEquals(InstanceState.Active, h.state(e.id).value)
    }

    @Test fun wallClockTimeoutIsETimeout() {
        val h = host(mapOf(WasmSetting.CALL_TIMEOUT_MS.key to 150, WasmSetting.FUEL_PER_CALL.key to Int.MAX_VALUE))
        val e = ext()
        activate(h, e)
        val t0 = System.nanoTime()
        val r = h.cmd(e, "test.spin") as HostResult.Err
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(ErrorCode.E_TIMEOUT, r.code)
        assertTrue("took $ms ms", ms < 2_000)
    }

    @Test fun timeInsideAHostFunctionDoesNotCountAgainstTheTimeout() {
        val h = host(mapOf(WasmSetting.CALL_TIMEOUT_MS.key to 150))
        val e = ext()
        activate(h, e)
        val gate = CompletableDeferred<Unit>()
        fake.quickPickGate = gate
        val release = Thread { Thread.sleep(400); gate.complete(Unit) }.apply { start() }
        val reply = h.proxy(e, "ui.showQuickPick", buildJsonObject { put("items", JsonArray(emptyList())) })
        release.join()
        assertEquals("picked", reply["result"]!!.jsonPrimitive.content)
    }

    @Test fun hostCallStormIsELimitAtTheCap() {
        val h = host(mapOf(WasmSetting.MAX_HOST_CALLS_PER_CALL.key to 10))
        val e = ext()
        activate(h, e)
        val r = h.cmd(e, "test.storm") as HostResult.Err
        assertEquals(ErrorCode.E_LIMIT, r.code)
        assertTrue(r.message, r.message.contains("host calls"))
    }

    @Test fun memoryGrowPastTheCapReturnsMinusOne() {
        val capped = host(mapOf(WasmSetting.MAX_MEMORY_MB.key to 2))
        val e = ext()
        activate(capped, e)
        assertEquals("-1", ((capped.cmd(e, "test.grow") as HostResult.Ok).result as JsonPrimitive).content)
    }

    @Test fun memoryGrowWithinTheCapSucceeds() {
        val h = host()
        val e = ext()
        activate(h, e)
        assertEquals("1", ((h.cmd(e, "test.grow") as HostResult.Ok).result as JsonPrimitive).content)
    }

    @Test fun malformedReplyDiscardsTheInstance() {
        val h = host()
        val e = ext()
        activate(h, e)
        for (c in listOf("test.badReply", "test.badJson")) {
            val r = h.cmd(e, c) as HostResult.Err
            assertEquals(ErrorCode.E_INTERNAL, r.code)
            assertEquals(InstanceState.Unloaded, h.state(e.id).value)
        }
    }

    @Test fun deepRecursionIsELimitNotACrash() {
        val h = host()
        val e = ext()
        activate(h, e)
        assertEquals(ErrorCode.E_LIMIT, (h.cmd(e, "test.recurse") as HostResult.Err).code)
    }

    @Test fun trapIsEInternalAndThreeWithinTheWindowDisable() {
        val h = host()
        val e = ext()
        activate(h, e)
        repeat(2) {
            assertEquals(ErrorCode.E_INTERNAL, (h.cmd(e, "test.trap") as HostResult.Err).code)
            now.addAndGet(1_000)
        }
        assertTrue(disabled.isEmpty())
        h.cmd(e, "test.trap")
        assertEquals(listOf(e.id), disabled)
        assertTrue(h.state(e.id).value is InstanceState.Disabled)
        assertTrue(fake.registeredCommands.isEmpty())
        assertTrue(h.providers.value.isEmpty())
        assertEquals(ErrorCode.E_UNAVAILABLE, (h.cmd(e, "test.noReply") as HostResult.Err).code)
        // Re-enabling (a new activate) starts a fresh window.
        assertEquals(HostResult.Ok(null), activate(h, e))
        h.cmd(e, "test.trap")
        assertEquals(1, disabled.size)
    }

    @Test fun trapsSpreadBeyondTheWindowDoNotDisable() {
        val h = host()
        val e = ext()
        activate(h, e)
        repeat(3) {
            h.cmd(e, "test.trap")
            now.addAndGet(151_000)
        }
        assertTrue(disabled.isEmpty())
    }

    @Test fun eventsReachSubscribersOnlyAndHonourThePathRule() {
        val h = host()
        val e = ext(listOf(Cap.FS_READ))
        activate(h, e)
        h.postEvent(e.id, "workspace.didOpen", buildJsonObject { put("path", "/workspace/x") })
        h.postEvent(e.id, "workspace.didSave", buildJsonObject { put("path", "/etc/passwd") })
        h.postEvent(e.id, "workspace.didSave", buildJsonObject { put("path", "/workspace/a.py") })
        val event = awaitStored(e.id, "event")
        assertEquals("workspace.didSave", event["event"]!!.jsonPrimitive.content)
        assertEquals("/workspace/a.py", event["data"]!!.jsonObject["path"]!!.jsonPrimitive.content)
    }

    @Test fun sandboxOutputIsDeliveredAsEventsWithTheHandle() {
        val h = host()
        val e = ext(listOf(Cap.SANDBOX_EXEC))
        activate(h, e)
        val reply = h.proxy(e, "sandbox.exec", buildJsonObject {
            put("argv", JsonArray(listOf(JsonPrimitive("ls"))))
            put("env", buildJsonObject { put("EASYIDE_GIT_TOKEN", "x"); put("A", "1") })
        })
        val handle = reply["result"]!!.jsonObject["handle"]!!.jsonPrimitive.content.toLong()
        assertEquals(mapOf("A" to "1"), fake.execs.single().env)
        fake.sinks.single().finish("sandbox.exit", buildJsonObject { put("code", 0) })
        val event = awaitStored(e.id, "event")
        assertEquals("sandbox.exit", event["event"]!!.jsonPrimitive.content)
        assertEquals(handle, event["data"]!!.jsonObject["handle"]!!.jsonPrimitive.content.toLong())
    }

    @Test fun providerRequestIsAnsweredWithTheGuestResult() {
        val h = host()
        val e = ext()
        activate(h, e)
        val r = runBlocking { h.providerRequest(e.id, "provider.completion", buildJsonObject { put("uri", "file:///workspace/a.py") }) }
        assertEquals("wasm", ((r as HostResult.Ok).result as JsonArray)[0].jsonObject["label"]!!.jsonPrimitive.content)
        val hover = runBlocking { h.providerRequest(e.id, "provider.hover", EMPTY) }
        assertEquals(ErrorCode.E_NOT_FOUND, (hover as HostResult.Err).code)
    }

    @Test fun reentrantCallIntoTheBusyInstanceIsRefused() {
        val h = host()
        val e = ext()
        activate(h, e)
        // commands.execute of a foreign command whose implementation calls back into e.
        fake.onExecute = { h.executeCommand(e.id, "test.noReply", JsonArray(emptyList()), EMPTY).let { JsonPrimitive(it.toString()) } }
        val reply = h.proxy(e, "commands.execute", buildJsonObject { put("command", "other.cmd") })
        assertTrue(reply["result"]!!.jsonPrimitive.content, reply["result"]!!.jsonPrimitive.content.contains("E_UNAVAILABLE"))
        // Its own command directly: refused before reaching the registry.
        val own = h.proxy(e, "commands.execute", buildJsonObject { put("command", "test.proxy") })
        assertEquals("E_UNAVAILABLE", own["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test fun activationFailuresAreReported() {
        val h = host()
        val refused = ext(name = "bad_abi_version")
        val r = activate(h, refused) as HostResult.Err
        assertTrue(r.message, r.message.contains("ext_abi_version returned 2"))
        assertTrue(h.state(refused.id).value is InstanceState.Failed)

        val h2 = host()
        val e = ext()
        val fail = activate(h2, e, context.copy(settings = buildJsonObject { put("failActivation", true) })) as HostResult.Err
        assertEquals(ErrorCode.E_ARGS, fail.code)
        val trap = activate(h2, e, context.copy(settings = buildJsonObject { put("trapActivation", true) })) as HostResult.Err
        assertEquals(ErrorCode.E_INTERNAL, trap.code)
    }

    @Test fun slowDeactivateIsDroppedAfterTheTimeout() {
        val h = host(mapOf(WasmSetting.DEACTIVATE_TIMEOUT_MS.key to 150, WasmSetting.FUEL_PER_CALL.key to Int.MAX_VALUE))
        val e = ext()
        activate(h, e)
        h.cmd(e, "test.slowDeactivate")
        val t0 = System.nanoTime()
        runBlocking { h.deactivate(e.id) }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
        assertEquals(InstanceState.Unloaded, h.state(e.id).value)
        assertEquals(ErrorCode.E_NOT_FOUND, (h.cmd(e, "test.noReply") as HostResult.Err).code)
        assertTrue(fake.watches.contains("unwatch ${e.id}"))
    }

    @Test fun masterSwitchRefusesActivation() {
        val h = host(mapOf(WASM_ENABLED_KEY to false))
        assertEquals(ErrorCode.E_UNAVAILABLE, (activate(h, ext()) as HostResult.Err).code)
    }

    @Test fun trimMemoryDropsIdleInstancesThatComeBackOnDemand() {
        val h = host()
        val e = ext()
        activate(h, e)
        h.trimMemory()
        runBlocking { withTimeout(5_000) { while (h.state(e.id).value != InstanceState.Unloaded) delay(10) } }
        assertTrue(h.cmd(e, "test.noReply") is HostResult.Ok)
    }

    private fun awaitStored(id: String, key: String): JsonObject = runBlocking {
        withTimeout(5_000) {
            while (fake.stored(id, key) == null) delay(10)
        }
        fake.stored(id, key)!!.jsonObject
    }
}
