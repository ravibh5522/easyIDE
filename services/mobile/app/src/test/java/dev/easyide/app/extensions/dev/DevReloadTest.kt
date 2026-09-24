package dev.easyide.app.extensions.dev

import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.extensions.host.InstalledPackage
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.ManifestParser
import dev.easyide.extensions.manifest.PackageLayout
import dev.easyide.extensions.manifest.PackageLayoutReader
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.ParseOptions
import dev.easyide.extensions.manifest.ParseResult
import dev.easyide.extensions.manifest.Source
import dev.easyide.extensions.schema.ManifestSchema
import dev.easyide.sandbox.extensions.ExtensionVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class DevReloadTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A pack declaring [caps]; `sandbox.exec` packs run a terminal command and install per environment. */
    private fun descriptor(caps: List<String> = listOf("clipboard")): ExtensionDescriptor {
        val dir = tmp.newFolder()
        val env = "sandbox.exec" in caps
        val action = if (env) """{ "type": "runInTerminal", "command": "ls" }""" else """{ "type": "showMessage", "text": "hi" }"""
        val scope = if (env) "\"scope\": \"environment\", " else ""
        File(dir, "package.json").writeText(
            """{ "name": "run", "publisher": "acme", "version": "1.0.0", "engines": { "easyide": "^0.3.0" },
               "easyide": { $scope"capabilities": [${caps.joinToString { "\"$it\"" }}], "actions": { "acme.run.go": $action } },
               "contributes": { "commands": [ { "command": "acme.run.go", "title": "Go" } ] } }""",
        )
        val files = (PackageLayoutReader.read(dir, PackageLimits.DEFAULT) as PackageLayout.Ok).files
        val r = ManifestParser(ManifestSchema.validator, ParseOptions(builtInCommands = CommandIds.ALL)).parse(files)
        return (r as? ParseResult.Ok ?: error(r.toString())).descriptor
    }

    private fun pkg(id: String, source: Source, approved: Set<String>, scope: InstallScope = InstallScope.GLOBAL, envId: String? = null) =
        InstalledPackage(File(tmp.root, "$id/1.0.0"), scope, envId, source, 1L, approved, revoked = false, crashDisabled = false)

    @Test fun `first developer install prompts`() {
        val d = descriptor()
        assertEquals(DevDecision.Prompt(PromptReason.FIRST_INSTALL, null, setOf("clipboard")), DevReload.decide("acme.run", d, emptyList(), null))
    }

    @Test fun `identical capabilities over a developer install reload silently`() {
        val d = descriptor()
        assertEquals(DevDecision.Silent(null), DevReload.decide("acme.run", d, listOf(pkg("acme.run", Source.DEV, setOf("clipboard"))), null))
    }

    @Test fun `changed capabilities or a non-developer copy prompt`() {
        val d = descriptor(listOf("clipboard", "ui.settings"))
        val r = DevReload.decide("acme.run", d, listOf(pkg("acme.run", Source.DEV, setOf("clipboard"))), null)
        assertEquals(DevDecision.Prompt(PromptReason.CAPABILITIES_CHANGED, null, setOf("ui.settings")), r)
        // Fewer capabilities are a change too: the approval must match what is declared.
        val fewer = DevReload.decide("acme.run", descriptor(), listOf(pkg("acme.run", Source.DEV, setOf("clipboard", "ui.settings"))), null)
        assertTrue(fewer is DevDecision.Prompt && fewer.reason == PromptReason.CAPABILITIES_CHANGED)
        val registry = DevReload.decide("acme.run", descriptor(), listOf(pkg("acme.run", Source.REGISTRY, setOf("clipboard"))), null)
        assertTrue(registry is DevDecision.Prompt && registry.reason == PromptReason.REPLACES_NON_DEV)
    }

    @Test fun `built-ins and other ids do not count as a prior install`() {
        val r = DevReload.decide("acme.run", descriptor(), listOf(pkg("acme.run", Source.BUILT_IN, emptySet()), pkg("acme.other", Source.DEV, setOf("clipboard"))), null)
        assertTrue(r is DevDecision.Prompt && r.reason == PromptReason.FIRST_INSTALL)
    }

    @Test fun `a package whose id differs from the request is refused`() {
        assertTrue(DevReload.decide("acme.other", descriptor(), emptyList(), null) is DevDecision.Refuse)
    }

    @Test fun `environment packs reload into their environment, else the open one`() {
        val d = descriptor(listOf("sandbox.exec"))
        val prior = pkg("acme.run", Source.DEV, setOf("sandbox.exec"), InstallScope.ENVIRONMENT, "env1")
        assertEquals(DevDecision.Silent("env1"), DevReload.decide("acme.run", d, listOf(prior), "env2"))
        assertEquals(DevDecision.Prompt(PromptReason.FIRST_INSTALL, "env2", setOf("sandbox.exec")), DevReload.decide("acme.run", d, emptyList(), "env2"))
    }

    @Test fun `dev versions are valid, distinct and keep a prerelease`() {
        assertEquals("1.2.0-dev.17", DevReload.devVersion("1.2.0", 17))
        assertEquals("1.2.0-beta.1.dev.17", DevReload.devVersion("1.2.0-beta.1", 17))
        assertTrue(ExtensionVersion.parseOrNull(DevReload.devVersion("1.2.0", 1_727_190_000_123)) != null)
        val m = DevReload.withDevVersion("""{"name":"a","version":"0.1.0","x":[1]}""", 5)!!
        assertEquals("""{"name":"a","version":"0.1.0-dev.5","x":[1]}""", m)
        assertNull(DevReload.withDevVersion("{", 5))
        assertNull(DevReload.withDevVersion("""{"version":1}""", 5))
    }

    @Test fun `the inbox needs a valid id and its archive`() {
        val inbox = tmp.newFolder("dev-inbox")
        assertTrue(DevReload.locateInbox(inbox, null) is Located.Refused)
        assertTrue(DevReload.locateInbox(inbox, "../acme.run") is Located.Refused)
        assertTrue(DevReload.locateInbox(inbox, "Acme.Run") is Located.Refused)
        assertTrue(DevReload.locateInbox(inbox, "acme.run") is Located.Refused)
        File(inbox, "acme.run.easyext").writeText("PK")
        assertEquals(Located.Found(DevSource.Archive("acme.run", File(inbox, "acme.run.easyext"))), DevReload.locateInbox(inbox, "acme.run"))
    }

    @Test fun `local requests map the folder into the project and refuse escapes`() {
        val root = tmp.newFolder("project")
        File(root, "exts/run").mkdirs()
        fun req(folder: String, id: String = "acme.run") = """{"id":"$id","folder":"$folder","requestedAt":"2026-09-24T00:00:00Z"}"""
        assertEquals(Located.Found(DevSource.Folder("acme.run", File(root, "exts/run").canonicalFile)), DevReload.locateRequest(req("exts/run"), "acme.run.json", root))
        assertEquals(Located.Found(DevSource.Folder("acme.run", root.canonicalFile)), DevReload.locateRequest(req("."), "acme.run.json", root))
        listOf("../outside", "/etc", "exts/../../x", "missing").forEach { f ->
            assertTrue(f, DevReload.locateRequest(req(f), "acme.run.json", root) is Located.Refused)
        }
        assertTrue(DevReload.locateRequest(req("exts/run"), "acme.other.json", root) is Located.Refused)
        assertTrue(DevReload.locateRequest("[]", "acme.run.json", root) is Located.Refused)
        assertTrue(DevReload.locateRequest("{", "acme.run.json", root) is Located.Refused)
        val outside = tmp.newFolder("outside")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertTrue(DevReload.locateRequest(req("link"), "acme.run.json", root) is Located.Refused)
    }

    @Test fun `request files are the json files of the request dir`() {
        val dir = File(tmp.root, "p/.easyide/dev")
        assertEquals(emptyList<File>(), DevReload.requestFiles(dir))
        dir.mkdirs()
        File(dir, "b.c.json").writeText("{}")
        File(dir, "a.b.json").writeText("{}")
        File(dir, "notes.txt").writeText("")
        assertEquals(listOf("a.b.json", "b.c.json"), DevReload.requestFiles(dir).map { it.name })
    }
}
