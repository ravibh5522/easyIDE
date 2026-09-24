package dev.easyide.extensions

import dev.easyide.extensions.action.ActionOutcome
import dev.easyide.extensions.action.FakeHost
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.contrib.CommandContribution
import dev.easyide.extensions.contrib.Contributions
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.host.ActivationState
import dev.easyide.extensions.host.CrashJournal
import dev.easyide.extensions.host.DisabledReason
import dev.easyide.extensions.host.ExtensionInventory
import dev.easyide.extensions.host.InstalledPackage
import dev.easyide.extensions.host.SafeModeReason
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.Source
import dev.easyide.extensions.settings.RuntimeScope
import dev.easyide.extensions.whenclause.ContextKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** End to end over real package directories: discover, validate, enable, register, run. */
class ExtensionsRuntimeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob())
    private val settings = FakeSettings()
    private val host = FakeHost()
    private val log = ArrayList<LogEntry>()
    private val python = ExtensionId.parse("easyide.python")!!

    private class Inventory(initial: List<InstalledPackage>) : ExtensionInventory {
        override val installed = MutableStateFlow(initial)
        override suspend fun setCrashDisabled(id: ExtensionId, disabled: Boolean) = Unit
    }

    @After fun tearDown() = scope.cancel()

    private fun copyFixture(name: String): File {
        val src = Fixtures.dir(name)
        return tmp.newFolder().also { src.copyRecursively(it, overwrite = true) }
    }

    private val pythonCaps = setOf("sandbox.exec", "sandbox.install", "lsp.spawn", "network(pypi.org,files.pythonhosted.org)")

    private fun pythonPkg(approved: Set<String> = pythonCaps) =
        InstalledPackage(copyFixture("python"), InstallScope.ENVIRONMENT, "env1", Source.REGISTRY, 10, approved, false, false)

    private fun themePkg() = InstalledPackage(copyFixture("theme"), InstallScope.GLOBAL, null, Source.SIDELOAD, 5, emptySet(), false, false)

    private fun runtime(inventory: Inventory, launcherSafeMode: Boolean = false, journal: File = File(tmp.root, "journal.json")) =
        ExtensionsRuntime(
            RuntimePorts(settings, inventory, host, { log += it }, journal), scope,
            builtIn = Contributions(commands = listOf(CommandContribution("workbench.action.files.save", "Save", null, null, null, null))),
            launcherSafeMode = launcherSafeMode,
        ).apply { setRuntimeScope(RuntimeScope("env1", "proj")); start() }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test fun `installed packs are validated, enabled in order and their contributions registered`() {
        val rt = runtime(Inventory(listOf(pythonPkg(), themePkg())))
        val set = await { rt.extensions.enabled.first { it.extensions.size == 2 } }
        assertEquals(listOf("acme.graphite-night", "easyide.python"), set.extensions.map { it.id.value })
        await { rt.contributions.snapshot.first { s -> s.commands.any { it.value.command == "python.runFile" } && s.themes.isNotEmpty() } }
        assertEquals(Owner.Ext(python), rt.contributions.commandOwner("python.runFile"))
        assertEquals("Graphite Night", rt.contributions.themes.entries.value.single().value.label)
        assertEquals(setOf("**/pyproject.toml"), rt.workspaceGlobs())
        await { rt.activation.states.first { it[python] == ActivationState.ACTIVE } }
        await { rt.contextKeys.snapshot.first { it[ContextKeys.extensionEnabled("easyide.python").name] == JsonPrimitive(true) } }
    }

    @Test fun `commands run through the action engine with the pack's grants`() {
        val rt = runtime(Inventory(listOf(pythonPkg())))
        await { rt.contributions.snapshot.first { s -> s.commands.any { it.value.command == "python.runFile" } } }
        rt.contextKeys.set(ContextKeys.envState, "ready")
        settings.set("python.interpreter", "\"python3\"")
        val out = await { rt.run("python.runFile") }
        assertEquals(ActionOutcome.Done(JsonNull), out)
        assertEquals("cd '/workspace/src' && 'python3' '/workspace/src/my file.py'", host.terminal.single().commandLine)
        assertEquals("Python", host.terminal.single().terminalName)
        assertEquals(ActionOutcome.Done(JsonNull), await { rt.run("workbench.action.files.save") })
    }

    @Test fun `unapproved capabilities, disabled list and environment keep packs off`() {
        val rt = runtime(Inventory(listOf(pythonPkg(approved = pythonCaps - "lsp.spawn"), themePkg())))
        await { rt.extensions.enabled.first { it.extensions.size == 1 } }
        assertEquals(DisabledReason.NEEDS_APPROVAL, rt.extensions.disabledReasons.value[python])
        settings.set("extensions.disabled", """["acme.graphite-night"]""")
        await { rt.extensions.enabled.first { it.extensions.isEmpty() } }
        await { rt.contributions.themes.entries.first { it.isEmpty() } }
        assertEquals(DisabledReason.USER_DISABLED, rt.extensions.disabledReasons.value[ExtensionId.parse("acme.graphite-night")!!])
    }

    @Test fun `packages that fail validation are reported, not loaded`() {
        val broken = themePkg().also { File(it.directory, "package.json").writeText("""{ "name": "x" }""") }
        val rt = runtime(Inventory(listOf(broken)))
        val problems = await { rt.extensions.problems.first { it.isNotEmpty() } }
        assertTrue(problems.single().errors.isNotEmpty())
        assertTrue(rt.extensions.enabled.value.extensions.isEmpty())
    }

    @Test fun `two crashed starts in a row bring up automatic safe mode`() {
        val journal = File(tmp.root, "crash.json")
        CrashJournal(journal) { }.apply { onStartup(); begin(python, CrashJournal.Phase.ACTIVATE) }
        CrashJournal(journal) { }.apply { onStartup(); begin(python, CrashJournal.Phase.REGISTER_CONTRIBUTIONS) }
        val rt = runtime(Inventory(listOf(pythonPkg(), themePkg())), journal = journal)
        assertTrue(rt.startupVerdict.enterSafeMode)
        assertEquals(listOf(python), rt.safeMode.suspects.value)
        assertEquals(SafeModeReason.AUTO_CRASH, rt.safeMode.active.value)
        await { rt.extensions.loaded.first { it.size == 2 } }
        await { rt.contextKeys.snapshot.first { it[ContextKeys.isSafeMode.name] == JsonPrimitive(true) } }
        assertTrue(rt.extensions.enabled.value.extensions.isEmpty())
        rt.safeMode.exitSession()
        await { rt.extensions.enabled.first { it.extensions.size == 2 } }
    }

    @Test fun `launcher shortcut and the setting also mean safe mode`() {
        assertEquals(SafeModeReason.LAUNCHER_SHORTCUT, runtime(Inventory(emptyList()), launcherSafeMode = true).safeMode.active.value)
        settings.set("extensions.safeMode", "true")
        assertEquals(SafeModeReason.SETTING, runtime(Inventory(emptyList()), journal = File(tmp.root, "j2.json")).safeMode.active.value)
    }
}
