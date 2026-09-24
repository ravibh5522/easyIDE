package dev.easyide.extwasm

import dev.easyide.extwasm.host.CapabilitySet
import dev.easyide.extwasm.host.WasmExtension
import dev.easyide.extwasm.load.WasmModuleLoader
import dev.easyide.extwasm.load.WasmStaticCheck
import dev.easyide.extwasm.testing.FakePorts
import dev.easyide.extwasm.testing.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * M7 exit (arch.md sec 12): the Rust sample built with the `easyide-guest` crate
 * (services/shared/samples/wasm-word-count) loads, activates and answers its command through
 * the real host, host functions and capability checks.
 */
class WasmSampleTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val module = File("../../shared/samples/wasm-word-count/wasm/main.wasm")
    private val fake = FakePorts()

    @After fun tearDown() { scope.cancel() }

    private fun sample(caps: List<String>) = WasmExtension(
        id = "easyide-samples.wasm-word-count", version = "0.1.0", moduleFile = module,
        moduleSha256 = MessageDigest.getInstance("SHA-256").digest(module.readBytes()).joinToString("") { "%02x".format(it) },
        manifestMemoryMb = 16, capabilities = CapabilitySet(caps), commands = setOf(COMMAND),
    )

    private val context = ActivationContext(apiVersion = "0.3.0", settings = JsonObject(emptyMap()), env = EnvInfo("env1", "ubuntu", "arm64"))

    @Test fun `the sample passes the static check the installer and CLI run`() {
        assertNull(WasmStaticCheck.reject(module.readBytes(), WasmLimits.resolve(MapSettings(), 16)))
    }

    @Test fun `the Rust sample registers and runs its command through the real host`() {
        val host = WasmHost(WasmModuleLoader(tmp.newFolder()), fake.ports, MapSettings(), { _, _ -> }, scope)
        val e = sample(listOf("fs.project(read)"))
        assertEquals(HostResult.Ok(null), runBlocking { host.activate(e, context) })
        assertEquals(listOf(e.id to COMMAND), fake.registeredCommands)
        fake.editorText = "one two  three\nfour"
        val r = runBlocking { host.executeCommand(e.id, COMMAND, JsonArray(emptyList()), JsonObject(emptyMap())) }
        assertEquals(HostResult.Ok(JsonPrimitive(4)), r)
        assertEquals("4 words", fake.messages.single()["text"]!!.jsonPrimitive.content)
        host.close()
    }

    @Test fun `without fs read the host refuses the guest's editor read`() {
        val host = WasmHost(WasmModuleLoader(tmp.newFolder()), fake.ports, MapSettings(), { _, _ -> }, scope)
        val e = sample(emptyList())
        runBlocking { host.activate(e, context) }
        val r = runBlocking { host.executeCommand(e.id, COMMAND, JsonArray(emptyList()), JsonObject(emptyMap())) }
        assertEquals(ErrorCode.E_CAPABILITY, (r as HostResult.Err).code)
        assertEquals(emptyList<Any>(), fake.messages)
        host.close()
    }

    private companion object {
        const val COMMAND = "wasm-word-count.count"
    }
}
