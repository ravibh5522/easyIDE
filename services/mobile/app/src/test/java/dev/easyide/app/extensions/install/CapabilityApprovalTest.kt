package dev.easyide.app.extensions.install

import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.extensions.host.Enablement
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.ManifestParser
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.ParseOptions
import dev.easyide.extensions.manifest.Source
import dev.easyide.extensions.schema.ManifestSchema
import dev.easyide.sandbox.SandboxPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CapabilityApprovalTest {

    @get:Rule val tmp = TemporaryFolder()

    private val manifest = """
        { "name": "run", "publisher": "acme", "version": "1.0.0", "engines": { "easyide": "^0.3.0" },
          "easyide": { "capabilities": ["sandbox.exec", "clipboard"], "actions": { "acme.run.go": { "type": "runInTerminal", "command": "ls" } } },
          "contributes": { "commands": [ { "command": "acme.run.go", "title": "Go" } ] } }
    """.trimIndent()

    private fun zip(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("package.json")); z.write(manifest.toByteArray()); z.closeEntry() }
        return out.toByteArray()
    }

    private data class Installed(val installer: LocalInstaller, val inventory: DiskExtensionInventory, val descriptor: ExtensionDescriptor)

    private suspend fun installed(): Installed {
        val paths = SandboxPaths(tmp.newFolder("files")).also { it.ensureBaseDirs() }
        val state = ExtensionStateStore(paths.extensionStateFile)
        val inventory = DiskExtensionInventory(paths, { emptyList() }, state, Dispatchers.Unconfined)
        val installer = LocalInstaller(
            paths, state, inventory,
            ManifestParser(ManifestSchema.validator, ParseOptions(builtInCommands = CommandIds.ALL)),
            { PackageLimits.DEFAULT }, Dispatchers.Unconfined, clock = { 42L },
        )
        val staged = installer.stageArchive { ByteArrayInputStream(zip()) } as StageResult.Staged
        installer.commit(staged.pkg, envId = "env1")
        return Installed(installer, inventory, staged.pkg.descriptor)
    }

    @Test fun `revoking one capability leaves the others approved and grant restores it`() = runTest {
        val (installer, inventory) = installed()
        assertEquals(setOf("sandbox.exec", "clipboard"), inventory.installed.value.single().approvedCapabilities)

        installer.setApproved(inventory.installed.value.single(), "sandbox.exec", approved = false)
        assertEquals(setOf("clipboard"), inventory.installed.value.single().approvedCapabilities)

        installer.setApproved(inventory.installed.value.single(), "sandbox.exec", approved = true)
        assertEquals(setOf("sandbox.exec", "clipboard"), inventory.installed.value.single().approvedCapabilities)
    }

    @Test fun `a revoked capability leaves the pack ungranted for it`() = runTest {
        val (installer, inventory, d) = installed()
        fun granted() = Enablement.granted(inventory.installed.value.single(), d).items.map { it.id }.toSet()
        assertEquals(setOf("sandbox.exec", "clipboard"), granted())

        installer.setApproved(inventory.installed.value.single(), "clipboard", approved = false)
        assertEquals(setOf("sandbox.exec"), granted())
    }

    @Test fun `built-in packs have no approvals to edit`() = runTest {
        val (installer, inventory) = installed()
        val builtIn = inventory.installed.value.single().copy(source = Source.BUILT_IN)
        var refused = false
        try { installer.setApproved(builtIn, "clipboard", approved = false) } catch (e: IllegalArgumentException) { refused = true }
        assertTrue(refused)
    }
}
