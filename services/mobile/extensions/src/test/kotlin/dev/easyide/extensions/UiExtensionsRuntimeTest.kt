package dev.easyide.extensions

import dev.easyide.extensions.action.FakeHost
import dev.easyide.extensions.contrib.Contributions
import dev.easyide.extensions.host.DisabledReason
import dev.easyide.extensions.host.ExtensionInventory
import dev.easyide.extensions.host.InstalledPackage
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.Source
import dev.easyide.extensions.settings.RuntimeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The shell points reach the registry only for enabled, approved packs in the right environment. */
class UiExtensionsRuntimeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob())
    private val settings = FakeSettings()
    private val docker = ExtensionId.parse("acme.docker")!!
    private val caps = setOf("ui.contribute", "ui.stage", "sandbox.exec")

    private class Inventory(initial: List<InstalledPackage>) : ExtensionInventory {
        override val installed = MutableStateFlow(initial)
        override suspend fun setCrashDisabled(id: ExtensionId, disabled: Boolean) = Unit
    }

    @After fun tearDown() = scope.cancel()

    private fun pkg(env: String = "env1", approved: Set<String> = caps, source: Source = Source.SIDELOAD) =
        InstalledPackage(tmp.newFolder().also { Fixtures.dir("ui-docker").copyRecursively(it, overwrite = true) }, InstallScope.ENVIRONMENT, env, source, 10, approved, false, false)

    private fun runtime(inventory: Inventory, launcherSafeMode: Boolean = false) =
        ExtensionsRuntime(
            RuntimePorts(settings, inventory, FakeHost(), {}, File(tmp.root, "journal.json")), scope,
            builtIn = Contributions.EMPTY, launcherSafeMode = launcherSafeMode,
        ).apply { setRuntimeScope(RuntimeScope("env1", "proj")); start() }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test fun `an approved pack in its environment contributes navigation, documents and presets`() {
        val rt = runtime(Inventory(listOf(pkg())))
        await { rt.contributions.navigation.entries.first { it.isNotEmpty() } }
        assertEquals(listOf("acme.docker.nav"), rt.contributions.navigation.entries.value.map { it.value.id })
        assertEquals(listOf("acme.docker/container"), rt.contributions.documents.entries.value.map { it.value.type })
        assertEquals(listOf("acme.docker.ops"), rt.contributions.layoutPresets.entries.value.map { it.value.id })
    }

    @Test fun `an unapproved ui contribute keeps the pack, and so its shell points, off`() {
        val rt = runtime(Inventory(listOf(pkg(approved = caps - "ui.contribute"))))
        await { rt.extensions.disabledReasons.first { it.containsKey(docker) } }
        assertEquals(DisabledReason.NEEDS_APPROVAL, rt.extensions.disabledReasons.value[docker])
        assertEquals(emptyList<String>(), rt.contributions.navigation.entries.value.map { it.value.id })
    }

    @Test fun `another environment keeps an environment pack off, switching brings it`() {
        val rt = runtime(Inventory(listOf(pkg(env = "env2"))))
        await { rt.extensions.disabledReasons.first { it.containsKey(docker) } }
        assertEquals(DisabledReason.OTHER_ENVIRONMENT, rt.extensions.disabledReasons.value[docker])
        assertEquals(0, rt.contributions.navigation.entries.value.size)
        rt.setRuntimeScope(RuntimeScope("env2", "proj"))
        await { rt.contributions.navigation.entries.first { it.isNotEmpty() } }
    }

    @Test fun `disabling the pack and safe mode both remove its shell points`() {
        val rt = runtime(Inventory(listOf(pkg())))
        await { rt.contributions.navigation.entries.first { it.isNotEmpty() } }
        settings.set("extensions.disabled", """["acme.docker"]""")
        await { rt.contributions.navigation.entries.first { it.isEmpty() } }
        settings.set("extensions.disabled", "[]")
        await { rt.contributions.navigation.entries.first { it.isNotEmpty() } }
        rt.safeMode.enterAuto(emptyList())
        await { rt.contributions.navigation.entries.first { it.isEmpty() } }
    }
}
