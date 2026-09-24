package dev.easyide.app.extensions.adapters

import dev.easyide.app.extensions.ExtFixtures
import dev.easyide.app.lsp.servers.InstallStep
import dev.easyide.extensions.capability.Capability
import dev.easyide.extensions.capability.CapabilitySet
import dev.easyide.extensions.host.EnabledExtension
import dev.easyide.extensions.host.EnabledSet
import dev.easyide.extensions.host.InstalledPackage
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.Source
import dev.easyide.extensions.whenclause.ContextLookup
import dev.easyide.lsp.protocol.LspFeature
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContributedServersTest {

    private val pack = ExtFixtures.descriptor(ExtFixtures.manifest("""
        "easyide": {
          "capabilities": ["lsp.spawn", "sandbox.install"],
          "languageServers": [
            { "id": "pyright", "languages": ["python"], "command": ["${'$'}{extensionPath}/bin/pyright", "--stdio"],
              "env": { "PY": "${'$'}{config:python.interpreter}" }, "memoryBudgetMb": 500, "priority": 2,
              "initializationOptions": { "a": 1 }, "features": { "only": ["diagnostics", "hover"] } },
            { "id": "ruff", "languages": ["python"], "command": ["ruff", "server"], "features": { "exclude": ["completion"] } }
          ],
          "sandbox": {
            "install": [
              { "id": "base", "title": "Base", "run": "apt-get install -y nodejs", "when": "envDistro in 'debian,ubuntu'" },
              { "id": "tools", "title": "Tools", "run": "npm i -g pyright --prefix ${'$'}{extensionPath}" }
            ],
            "verify": "pyright-langserver --version"
          }
        }
    """, name = "python"))

    private val snapshot = ExtFixtures.snapshot(pack)
    private val config = mapOf<String, JsonElement>("python.interpreter" to JsonPrimitive("/usr/bin/python3"))
    private val debian = ExtFixtures.context("envDistro" to "\"debian\"")

    private fun enabled(d: ExtensionDescriptor = pack, envId: String = ENV, granted: Set<Capability> = setOf(Capability.LspSpawn)) =
        EnabledSet(1, listOf(EnabledExtension(d, CapabilitySet(granted), installed(envId))))

    private fun installed(envId: String) = InstalledPackage(
        directory = File("/host/ext"), scope = InstallScope.ENVIRONMENT, envId = envId, source = Source.SIDELOAD,
        installedAt = 1, approvedCapabilities = emptySet(), revoked = false, crashDisabled = false,
    )

    private fun declare(
        set: EnabledSet = enabled(),
        values: Map<String, JsonElement> = config,
        context: ContextLookup = debian,
    ) = ContributedServers.declarations(ENV, snapshot.languageServers, snapshot.sandbox, set, values::get, context)

    @Test fun `servers of an enabled pack become declarations with variables expanded`() {
        val r = declare()
        assertTrue(r.skipped.isEmpty())
        val (pyright, ruff) = r.declarations
        val root = pack.guestRoot
        assertEquals("acme.python/pyright", pyright.key)
        assertEquals(listOf("$root/bin/pyright", "--stdio"), pyright.command)
        assertEquals(mapOf("PY" to "/usr/bin/python3"), pyright.env)
        assertEquals(setOf("python"), pyright.languages)
        assertEquals(500, pyright.memoryBudgetMb)
        assertEquals(2, pyright.priority)
        assertEquals("acme.python", pyright.extensionId)
        assertEquals(setOf(LspFeature.DIAGNOSTICS, LspFeature.HOVER), pyright.features.only)
        assertEquals(setOf(LspFeature.COMPLETION), ruff.features.exclude)
        assertNull(ruff.features.only)
        assertNull("an empty initializationOptions sends nothing", ruff.initializationOptions)
    }

    @Test fun `install recipe keeps the steps whose when holds and expands them`() {
        val recipe = declare().declarations.first().install!!
        assertEquals(
            listOf(InstallStep("Base", "apt-get install -y nodejs"), InstallStep("Tools", "npm i -g pyright --prefix ${pack.guestRoot}")),
            recipe.steps,
        )
        assertEquals("pyright-langserver --version", recipe.verify)
        val alpine = declare(context = ExtFixtures.context("envDistro" to "\"alpine\"")).declarations.first().install!!
        assertEquals(listOf("Tools"), alpine.steps.map { it.title })
    }

    @Test fun `servers follow their environment and the lsp spawn grant`() {
        assertTrue(declare(enabled(envId = "other")).declarations.isEmpty())
        assertTrue(declare(EnabledSet.EMPTY).declarations.isEmpty())
        val ungranted = declare(enabled(granted = emptySet()))
        assertTrue(ungranted.declarations.isEmpty())
        assertEquals(2, ungranted.skipped.size)
        assertTrue(ungranted.skipped.first().second.contains("lsp.spawn"))
    }

    @Test fun `an unset config variable skips only that server, with a reason`() {
        val r = declare(values = emptyMap())
        assertEquals(listOf("acme.python/ruff"), r.declarations.map { it.key })
        val (id, message) = r.skipped.single()
        assertEquals(pack.id, id)
        assertTrue(message, message.contains("python.interpreter"))
    }

    @Test fun `variables other than extensionPath and config are refused`() {
        val other = ExtFixtures.descriptor(ExtFixtures.manifest("""
            "easyide": { "capabilities": ["lsp.spawn"],
              "languageServers": [{ "id": "s", "languages": ["x"], "command": ["srv", "${'$'}{workspaceFolder}"] }] }
        """, name = "other"))
        val snap = ExtFixtures.snapshot(other)
        val r = ContributedServers.declarations(ENV, snap.languageServers, snap.sandbox, enabled(other), config::get, debian)
        assertTrue(r.declarations.isEmpty())
        assertTrue(r.skipped.single().second.contains("extensionPath"))
    }

    private companion object {
        const val ENV = "env-1"
    }
}
