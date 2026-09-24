package dev.easyide.extensions.host

import dev.easyide.extensions.FakeSettings
import dev.easyide.extensions.Manifests
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.capability.CapabilitySet
import dev.easyide.extensions.manifest.ActivationEvent
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.Source
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ActivationManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val settings = FakeSettings()
    private val log = ArrayList<LogEntry>()
    private var now = 0L

    private class FakeInventory : ExtensionInventory {
        override val installed: StateFlow<List<InstalledPackage>> = MutableStateFlow(emptyList())
        val crashDisabled = HashMap<ExtensionId, Boolean>()
        override suspend fun setCrashDisabled(id: ExtensionId, disabled: Boolean) { crashDisabled[id] = disabled }
    }

    private class FakeActivator(var result: ActivationResult = ActivationResult.Ok) : Activator {
        var activations = 0
        var deactivations = 0
        var gate: CompletableDeferred<Unit>? = null
        var hang = false
        override fun handles(d: ExtensionDescriptor) = d.wasm != null
        override suspend fun activate(d: ExtensionDescriptor, granted: CapabilitySet): ActivationResult {
            activations++
            gate?.await()
            if (hang) awaitCancellation()
            return result
        }
        override suspend fun deactivate(d: ExtensionDescriptor) { deactivations++ }
    }

    private val inventory = FakeInventory()
    private val activator = FakeActivator()
    private val journalFile by lazy { File(tmp.root, "j.json") }
    private val journal by lazy { CrashJournal(journalFile) { log += it } }
    private val manager by lazy { ActivationManager(listOf(activator), journal, inventory, settings, { now }, { log += it }) }

    private fun ext(name: String, wasm: Boolean, events: String = "\"onLanguage:python\""): EnabledExtension {
        val body = """"activationEvents": [$events]""" + if (wasm) """, "easyide": { "wasm": { "module": "m.wasm", "abi": 1 } }""" else ""
        val d = Manifests.ok(Manifests.minimal(body, name = name), mapOf("m.wasm" to "\u0000asm"))
        return EnabledExtension(d, d.capabilities, InstalledPackage(tmp.root, InstallScope.GLOBAL, null, Source.REGISTRY, 1, emptySet(), false, false))
    }

    private fun state(e: EnabledExtension) = manager.states.value[e.id]

    @Test fun `declarative-only extensions are active at once, wasm ones wait for their event`() = runTest {
        val decl = ext("decl", wasm = false)
        val logic = ext("logic", wasm = true)
        manager.onEnabledSetChanged(EnabledSet(1, listOf(decl, logic)))
        assertEquals(ActivationState.ACTIVE, state(decl))
        assertEquals(ActivationState.INACTIVE, state(logic))
        manager.onEvent(ActivationEvent.OnLanguage("rust"))
        assertEquals(0, activator.activations)
        manager.onEvent(ActivationEvent.OnLanguage("python"))
        assertEquals(ActivationState.ACTIVE, state(logic))
        manager.onEvent(ActivationEvent.OnLanguage("python"))
        assertEquals(1, activator.activations)
        assertTrue(journalFile.readText().contains("\"open\":[]"))
    }

    @Test fun `concurrent events join one activation`() = runTest(StandardTestDispatcher()) {
        val logic = ext("logic", wasm = true)
        manager.onEnabledSetChanged(EnabledSet(1, listOf(logic)))
        activator.gate = CompletableDeferred()
        val first = async { manager.onEvent(ActivationEvent.OnLanguage("python")) }
        val second = async { manager.ensureActive(logic.id) }
        runCurrent() // not advanceUntilIdle: that would also fire the virtual-time activation timeout
        assertEquals(ActivationState.ACTIVATING, state(logic))
        activator.gate!!.complete(Unit)
        first.await()
        assertEquals(ActivationState.ACTIVE, second.await())
        assertEquals(1, activator.activations)
    }

    @Test fun `failure and timeout end FAILED, which only an explicit retry leaves`() = runTest {
        val logic = ext("logic", wasm = true)
        manager.onEnabledSetChanged(EnabledSet(1, listOf(logic)))
        activator.result = ActivationResult.Failed("abi mismatch")
        assertEquals(ActivationState.FAILED, manager.ensureActive(logic.id))
        assertEquals(1, activator.deactivations)
        manager.onEvent(ActivationEvent.OnLanguage("python"))
        assertEquals(1, activator.activations)
        activator.result = ActivationResult.Ok
        activator.hang = true
        manager.retry(logic.id)
        assertEquals(ActivationState.FAILED, state(logic))
        assertTrue(log.any { it.message.contains("timed out after 5000ms") })
        activator.hang = false
        manager.retry(logic.id)
        assertEquals(ActivationState.ACTIVE, state(logic))
    }

    @Test fun `crashes re-activate on the next event until the crash budget in the window is spent`() = runTest {
        val logic = ext("logic", wasm = true)
        manager.onEnabledSetChanged(EnabledSet(1, listOf(logic)))
        manager.ensureActive(logic.id)
        manager.reportCrash(logic.id, "trap")
        assertEquals(ActivationState.CRASHED, state(logic))
        manager.onEvent(ActivationEvent.OnLanguage("python"))
        assertEquals(ActivationState.ACTIVE, state(logic))
        now += 301_000 // first crash leaves the 300 s window
        manager.reportCrash(logic.id, "trap")
        manager.ensureActive(logic.id)
        now += 1_000
        manager.reportCrash(logic.id, "trap")
        assertEquals(ActivationState.CRASHED, state(logic))
        manager.ensureActive(logic.id)
        manager.reportCrash(logic.id, "trap")
        assertEquals(ActivationState.CRASH_DISABLED, state(logic))
        assertEquals(true, inventory.crashDisabled[logic.id])
        manager.onEvent(ActivationEvent.OnLanguage("python"))
        assertEquals(ActivationState.CRASH_DISABLED, state(logic))
        manager.clearCrashDisable(logic.id)
        assertEquals(false, inventory.crashDisabled[logic.id])
        assertEquals(ActivationState.INACTIVE, state(logic))
    }

    @Test fun `disable deactivates an active extension`() = runTest {
        val logic = ext("logic", wasm = true)
        manager.onEnabledSetChanged(EnabledSet(1, listOf(logic)))
        manager.ensureActive(logic.id)
        manager.onEnabledSetChanged(EnabledSet(2, emptyList()))
        assertEquals(ActivationState.DISABLED, state(logic))
        assertEquals(1, activator.deactivations)
        assertEquals(ActivationState.DISABLED, manager.ensureActive(logic.id))
    }

    @Test fun `onStartupFinished and workspaceContains events match exactly`() = runTest {
        val a = ext("a", wasm = true, events = "\"*\"")
        val b = ext("b", wasm = true, events = "\"workspaceContains:**/Cargo.toml\"")
        manager.onEnabledSetChanged(EnabledSet(1, listOf(a, b)))
        manager.onEvent(ActivationEvent.OnStartupFinished)
        assertEquals(ActivationState.ACTIVE, state(a))
        assertEquals(ActivationState.INACTIVE, state(b))
        manager.onEvent(ActivationEvent.WorkspaceContains("**/Cargo.toml"))
        assertEquals(ActivationState.ACTIVE, state(b))
    }
}
