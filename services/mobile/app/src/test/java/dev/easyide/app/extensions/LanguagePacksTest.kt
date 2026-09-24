package dev.easyide.app.extensions

import dev.easyide.app.extensions.adapters.ContributedServers
import dev.easyide.extensions.capability.Capability
import dev.easyide.extensions.host.EnabledExtension
import dev.easyide.extensions.host.EnabledSet
import dev.easyide.extensions.host.Enablement
import dev.easyide.extensions.host.InstalledPackage
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.Source
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M4 language packs: each built-in pack that declares language servers must attach them to
 * languages the editor actually detects, and carry a setup recipe whose verify checks the
 * binaries it launches, so a missing server always offers a visible install.
 */
class LanguagePacksTest {

    private val languagePacks = BuiltInPackFixtures.ids().map { BuiltInPackFixtures.load(it).descriptor }
        .filter { it.contributes.languageServers.isNotEmpty() }

    /** Editor language ids: bundled grammar names plus languages packs contribute. */
    private val editorLanguages: Set<String> = run {
        val index = Json.parseToJsonElement(File("src/main/assets/grammars/index.json").readText()).jsonObject
        index.getValue("grammars").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet() +
            languagePacks.flatMap { d -> d.contributes.languages.map { it.id } }
    }

    @Test fun `the M4 language set is covered`() {
        val served = languagePacks.flatMap { d -> d.contributes.languageServers.flatMap { it.languages } }.toSet()
        val wanted = setOf("python", "typescript", "javascript", "tsx", "jsx", "go", "c", "cpp", "shellscript", "yaml",
            "json", "jsonc", "html", "css", "scss", "less", "markdown", "rust")
        assertEquals(emptySet<String>(), wanted - served)
    }

    @Test fun `servers attach to languages the editor detects`() {
        for (d in languagePacks) for (s in d.contributes.languageServers) {
            assertTrue("${s.key}: ${s.languages - editorLanguages}", editorLanguages.containsAll(s.languages))
        }
    }

    @Test fun `every language pack can put its servers in place, visibly`() {
        for (d in languagePacks) {
            val caps = d.capabilities.items
            assertTrue("${d.id} needs lsp.spawn", Capability.LspSpawn in caps)
            assertTrue("${d.id} needs sandbox.install", Capability.SandboxInstall in caps)
            val sandbox = d.contributes.sandbox
            assertTrue("${d.id} has no setup steps", sandbox != null && sandbox.install.isNotEmpty())
            assertTrue("${d.id} has no verify", sandbox!!.verify != null)
            assertTrue("${d.id} has no uninstall", sandbox.uninstall.isNotEmpty())
            assertTrue("${d.id} downloads without a network capability", caps.any { it is Capability.Network })
        }
    }

    @Test fun `built-in language packs declare their servers in any environment with the recipe`() {
        val config = mapOf<String, JsonElement>("python.interpreter" to JsonPrimitive("python3"))
        val ubuntu = ExtFixtures.context("envDistro" to "\"ubuntu\"")
        for (d in languagePacks) {
            val pkg = InstalledPackage(
                directory = File(d.root), scope = InstallScope.GLOBAL, envId = null, source = Source.BUILT_IN,
                installedAt = 0, approvedCapabilities = emptySet(), revoked = false, crashDisabled = false,
            )
            val snapshot = ExtFixtures.snapshot(d)
            val declared = ContributedServers.declarations(
                "any-env", snapshot.languageServers, snapshot.sandbox,
                EnabledSet(1, listOf(EnabledExtension(d, Enablement.granted(pkg, d), pkg))), config::get, ubuntu,
            )
            assertEquals("${d.id}: ${declared.skipped}", emptyList<Any>(), declared.skipped)
            assertEquals(d.contributes.languageServers.map { it.key }, declared.declarations.map { it.key.toString() })
            declared.declarations.forEach { assertTrue("${it.key} has no install recipe", it.install != null) }
        }
    }
}
