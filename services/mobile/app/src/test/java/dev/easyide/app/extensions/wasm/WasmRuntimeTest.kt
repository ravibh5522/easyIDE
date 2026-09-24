package dev.easyide.app.extensions.wasm

import dev.easyide.app.extensions.host.ActiveDocument
import dev.easyide.app.lsp.ProviderQuery
import dev.easyide.app.ui.screens.workspace.ext.WorkspaceEventDiff
import dev.easyide.lsp.protocol.Position
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.easyide.extensions.ExtensionsRuntime
import dev.easyide.extensions.FakeSettings
import dev.easyide.extensions.RuntimePorts
import dev.easyide.extensions.action.ActionError
import dev.easyide.extensions.action.ActionOutcome
import dev.easyide.extensions.action.FakeHost
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.host.ActivationState
import dev.easyide.extensions.host.ExtensionInventory
import dev.easyide.extensions.host.InstalledPackage
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.Source
import dev.easyide.extensions.settings.RuntimeScope
import dev.easyide.extwasm.host.HostInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M7 app integration: the Rust sample package installed on disk, discovered and enabled by
 * the real [ExtensionsRuntime], its command run through the action engine ->
 * `LogicHost` ([WasmRuntime]) -> activation -> [dev.easyide.extwasm.WasmHost] -> the guest,
 * which reads the (fake) active editor through the app's editor port and answers through
 * the same prompt port L1 actions use.
 */
class WasmRuntimeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = FakeSettings()
    private val host = FakeHost()
    private val log = CopyOnWriteArrayList<LogEntry>()
    private val bridge = FakeBridge(document = ActiveDocument("/workspace/notes.md", "markdown", 1, "one two  three\nfour", 0, 0, true))
    private val id = ExtensionId.parse("easyide-samples.wasm-word-count")!!
    private val disabled = CopyOnWriteArrayList<ExtensionId>()

    private class Inventory(initial: List<InstalledPackage>, val disabled: MutableList<ExtensionId>) : ExtensionInventory {
        override val installed = MutableStateFlow(initial)
        override suspend fun setCrashDisabled(id: ExtensionId, disabled: Boolean) { if (disabled) this.disabled += id }
    }

    @After fun tearDown() = scope.cancel()

    /** The sample as an unpacked install: manifest, module and license, no guest sources. */
    private fun install(manifest: (String) -> String = { it }): File {
        val dir = tmp.newFolder("easyide-samples.wasm-word-count", "0.1.0")
        File(WORD_COUNT_SAMPLE, "package.json").readText().let { File(dir, "package.json").writeText(manifest(it)) }
        File(WORD_COUNT_SAMPLE, "LICENSE").copyTo(File(dir, "LICENSE"))
        File(WORD_COUNT_SAMPLE, "wasm/main.wasm").copyTo(File(dir, "wasm/main.wasm"))
        return dir
    }

    private lateinit var extensionsDir: File

    private fun start(dir: File, approved: Set<String>, command: String = COMMAND, extId: ExtensionId = id): Pair<ExtensionsRuntime, WasmRuntime> {
        val pkg = InstalledPackage(dir, InstallScope.GLOBAL, null, Source.SIDELOAD, 1, approved, false, false)
        val inventory = Inventory(listOf(pkg), disabled)
        lateinit var runtime: ExtensionsRuntime
        val wasm = WasmRuntime(
            WasmDeps(
                extensionsDir = tmp.newFolder("extensions").also { extensionsDir = it },
                settings = settings,
                host = host,
                workspace = { bridge },
                rootfs = { null },
                clipboard = object : ClipboardAccess {
                    override fun read(): String? = null
                    override fun write(text: String) = Unit
                },
                log = { log += it },
                inventory = inventory,
                info = HostInfo("0.1.0-test", "0.3.0", "en-US"),
                scope = scope,
                io = Dispatchers.IO,
                main = Dispatchers.Unconfined,
            ),
        ) { runtime }
        runtime = ExtensionsRuntime(
            RuntimePorts(settings, inventory, host, { log += it }, File(tmp.root, "journal.json"), listOf(wasm.activator), wasm),
            scope,
        ).apply {
            setRuntimeScope(RuntimeScope("env1", "p1"))
            start()
        }
        wasm.start()
        await { runtime.contributions.snapshot.first { s -> s.commands.any { it.value.command == command } } }
        // Enabled but not live until its activation event arrives.
        await { runtime.activation.states.first { it[extId] == ActivationState.INACTIVE } }
        return runtime to wasm
    }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(20_000) { block() } }

    @Test fun `the Rust sample's command runs through the runtime and the WASM host`() {
        val (runtime, wasm) = start(install(), approved = setOf("fs.project(read)"))
        assertEquals(null, wasm.host.providers.value.firstOrNull())
        val out = await { runtime.run(COMMAND) }
        assertEquals(ActionOutcome.Done(JsonPrimitive(4)), out)
        assertEquals("4 words", host.messages.single().text)
        assertEquals(id, host.messages.single().owner)
        assertEquals(ActivationState.ACTIVE, runtime.activation.states.value[id])
        assertEquals(mapOf(id.value to setOf(COMMAND)), wasm.commands.registrations.value)
        // A second run reuses the live instance.
        bridge.document = bridge.document!!.copy(text = "just two")
        assertEquals(ActionOutcome.Done(JsonPrimitive(2)), await { runtime.run(COMMAND) })
        wasm.host.close()
    }

    @Test fun `a capability the manifest does not declare fails the command with E_CAPABILITY in the log`() {
        val dir = install { it.replace(""""capabilities": ["fs.project(read)"],""", "") }
        val (runtime, wasm) = start(dir, approved = emptySet())
        val out = await { runtime.run(COMMAND) }
        assertTrue("expected a failure, got $out", out is ActionOutcome.Failed)
        assertEquals(ActionError.CAPABILITY, (out as ActionOutcome.Failed).error)
        assertTrue(host.messages.isEmpty())
        val lines = log.filter { it.extensionId == id }.map { it.message }
        assertTrue(lines.toString(), lines.any { it.startsWith("denied editor.getText") && "fs.project(read)" in it })
        assertTrue(lines.toString(), lines.any { "E_CAPABILITY" in it && COMMAND in it })
        wasm.host.close()
    }

    @Test fun `with extensions wasm enabled off the extension does not activate and its command fails`() {
        settings.set("extensions.wasm.enabled", "false")
        val (runtime, wasm) = start(install(), approved = setOf("fs.project(read)"))
        val out = await { runtime.run(COMMAND) }
        assertTrue("expected a failure, got $out", out is ActionOutcome.Failed)
        assertEquals(ActivationState.FAILED, runtime.activation.states.value[id])
        assertTrue(log.toString(), log.any { "extensions.wasm.enabled" in it.message })
        assertTrue(host.messages.isEmpty())
        wasm.host.close()
    }

    @Test fun `disabling the extension deactivates its instance`() {
        val (runtime, wasm) = start(install(), approved = setOf("fs.project(read)"))
        await { runtime.run(COMMAND) }
        assertTrue(wasm.commands.registrations.value.containsKey(id.value))
        settings.set("extensions.disabled", """["${id.value}"]""")
        await { runtime.activation.states.first { it[id] == ActivationState.DISABLED } }
        await { wasm.host.state(id.value).first { it == dev.easyide.extwasm.InstanceState.Unloaded } }
        assertEquals(emptyMap<String, Set<String>>(), wasm.commands.registrations.value)
        wasm.host.close()
    }

    @Test fun `startup activation, completion providers and workspace events reach a live guest`() {
        val proxy = installProxy()
        val dir = proxyDir
        // Events carrying a path reach only extensions that may read it.
        val (runtime, wasm) = start(dir, approved = setOf("fs.project(read)"), command = "test.proxy", extId = proxy)
        await { runtime.onStartupFinished() }
        assertEquals(ActivationState.ACTIVE, runtime.activation.states.value[proxy])
        val query = ProviderQuery("file:///workspace/a.py", "python", 3, Position(0, 1))
        val items = await { wasm.completion(query) }
        assertEquals(listOf("acme.test" to listOf("wasm")), items.map { p -> p.source to p.items.map { it.label } })
        assertEquals(emptyList<Any>(), await { wasm.completion(query.copy(languageId = "rust")) })
        wasm.post(WorkspaceEventDiff.DID_SAVE, buildJsonObject { put("path", "/workspace/a.py") })
        val stored = File(extensionsDir, "storage/acme.test/global.json")
        await {
            while (!(stored.isFile && "workspace.didSave" in stored.readText())) delay(20)
        }
        wasm.host.close()
    }

    private fun installProxy(): ExtensionId {
        val dir = tmp.newFolder("acme.test", "1.0.0")
        File(dir, "package.json").writeText(PROXY_MANIFEST)
        AppWasmFixtures.file(File(dir, "wasm").apply { mkdirs() }, "proxy").renameTo(File(dir, "wasm/main.wasm"))
        return ExtensionId.parse("acme.test")!!.also { proxyDir = dir }
    }

    private lateinit var proxyDir: File

    @Test fun `repeated traps disable the extension through the crash-disable path with the reason logged`() {
        val proxy = installProxy()
        val (runtime, wasm) = start(proxyDir, approved = setOf("fs.project(read)"), command = "test.proxy", extId = proxy)
        repeat(3) {
            val out = await { runtime.run("test.trap") }
            assertTrue("expected a failure, got $out", out is ActionOutcome.Failed)
        }
        await { while (proxy !in disabled) delay(20) }
        assertTrue(log.toString(), log.any { it.extensionId == proxy && "Disabled after repeated crashes" in it.message })
        assertEquals(dev.easyide.extwasm.InstanceState.Disabled("Disabled after repeated crashes - re-enable in Extensions"), wasm.host.state(proxy.value).value)
        wasm.host.close()
    }

    @Test fun `switching the master switch off and on again re-activates on the next command`() {
        val (runtime, wasm) = start(install(), approved = setOf("fs.project(read)"))
        assertEquals(ActionOutcome.Done(JsonPrimitive(4)), await { runtime.run(COMMAND) })
        settings.set("extensions.wasm.enabled", "false")
        await { wasm.host.state(id.value).first { it == dev.easyide.extwasm.InstanceState.Unloaded } }
        assertEquals(ActionError.UNAVAILABLE, (await { runtime.run(COMMAND) } as ActionOutcome.Failed).error)
        settings.set("extensions.wasm.enabled", "true")
        assertEquals(ActionOutcome.Done(JsonPrimitive(4)), await { runtime.run(COMMAND) })
        wasm.host.close()
    }

    private companion object {
        const val COMMAND = "wasm-word-count.count"
        val PROXY_MANIFEST = """
            { "name": "test", "publisher": "acme", "version": "1.0.0", "engines": { "easyide": "^0.3.0" },
              "activationEvents": ["onStartupFinished"],
              "contributes": { "commands": [ { "command": "test.proxy", "title": "Proxy" }, { "command": "test.trap", "title": "Trap" } ] },
              "easyide": { "capabilities": ["fs.project(read)"], "wasm": { "module": "./wasm/main.wasm", "abi": 1,
                "providers": [ { "kind": "completion", "languages": ["python"] } ] } } }
        """.trimIndent()
    }
}
