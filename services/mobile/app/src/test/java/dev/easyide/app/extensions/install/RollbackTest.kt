package dev.easyide.app.extensions.install

import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.ManifestParser
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.ParseOptions
import dev.easyide.extensions.manifest.Source
import dev.easyide.extensions.schema.ManifestSchema
import dev.easyide.sandbox.SandboxPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RollbackTest {

    @get:Rule val tmp = TemporaryFolder()

    private val exec = """, "easyide": { "capabilities": ["sandbox.exec"], "actions": { "acme.run.go": { "type": "runInTerminal", "command": "ls" } } }, "contributes": { "commands": [ { "command": "acme.run.go", "title": "Go" } ] }"""

    private fun manifest(name: String, version: String, easyide: String = "") =
        """{ "name": "$name", "publisher": "acme", "version": "$version", "engines": { "easyide": "^0.3.0" }$easyide }"""

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    private class Setup(root: File, builtInDirs: List<File> = emptyList(), revoked: (String, String) -> Boolean = { _, _ -> false }) {
        val paths = SandboxPaths(root).also { it.ensureBaseDirs() }
        val state = ExtensionStateStore(paths.extensionStateFile)
        val inventory = DiskExtensionInventory(paths, { builtInDirs }, state, Dispatchers.Unconfined)
        val installer = LocalInstaller(
            paths, state, inventory,
            ManifestParser(ManifestSchema.validator, ParseOptions(builtInCommands = CommandIds.ALL)),
            { PackageLimits.DEFAULT }, Dispatchers.Unconfined, clock = { 42L }, isRevoked = revoked,
        )

        suspend fun install(name: String, version: String, easyide: String = "", envId: String? = null, test: RollbackTest) {
            val bytes = test.zip("package.json" to test.manifest(name, version, easyide))
            val staged = installer.stageArchive { ByteArrayInputStream(bytes) } as StageResult.Staged
            installer.commit(staged.pkg, envId)
        }

        fun active() = inventory.installed.value.single()
        fun idDir(id: String, envId: String? = null) = File(if (envId == null) paths.globalExtensionsDir else paths.environmentExtensionsDir(envId), id)
    }

    @Test fun `rollback flips current back, keeps approvals, and a second rollback rolls forward`() = runTest {
        val s = Setup(tmp.newFolder("files"))
        s.install("run", "1.0.0", exec, "env1", this@RollbackTest)
        s.install("run", "1.1.0", exec, "env1", this@RollbackTest)
        assertEquals(mapOf(s.active().directory.absolutePath to "1.0.0"), s.inventory.retained.value)
        assertEquals(RetainedVersion("1.0.0", setOf("sandbox.exec")), s.state.read().installs.single().previous)

        assertEquals(RollbackResult.Done("1.0.0"), s.installer.rollback("acme.run", InstallScope.ENVIRONMENT, "env1"))
        val back = s.active()
        assertEquals("1.0.0", back.directory.name)
        assertEquals(setOf("sandbox.exec"), back.approvedCapabilities)
        assertEquals(42L, back.installedAt)
        assertTrue(File(s.idDir("acme.run", "env1"), "1.1.0").isDirectory) // newer version retained
        val entry = s.state.read().installs.single()
        assertEquals("1.0.0", entry.version)
        assertEquals(RetainedVersion("1.1.0", setOf("sandbox.exec")), entry.previous)
        assertEquals(mapOf(back.directory.absolutePath to "1.1.0"), s.inventory.retained.value)

        assertEquals(RollbackResult.Done("1.1.0"), s.installer.rollback("acme.run", InstallScope.ENVIRONMENT, "env1"))
        assertEquals("1.1.0", s.active().directory.name)
        assertEquals("1.0.0", s.state.read().installs.single().previous?.version)
    }

    @Test fun `installing after a rollback retains the rolled-back-to version`() = runTest {
        val s = Setup(tmp.newFolder("files"))
        s.install("snips", "1.0.0", test = this@RollbackTest)
        s.install("snips", "1.1.0", test = this@RollbackTest)
        s.installer.rollback("acme.snips", InstallScope.GLOBAL, null)
        s.install("snips", "1.2.0", test = this@RollbackTest)
        assertEquals(listOf("1.0.0", "1.2.0", "current"), s.idDir("acme.snips").list()!!.sorted())
        assertEquals("1.0.0", s.state.read().installs.single().previous?.version)
    }

    @Test fun `nothing retained, not installed, or a built-in is refused without changes`() = runTest {
        val builtIn = File(tmp.newFolder("builtin"), "easyide.a/7").also { it.mkdirs() }
        val s = Setup(tmp.newFolder("files"), listOf(builtIn))
        s.install("snips", "1.0.0", test = this@RollbackTest)
        val before = s.state.read()
        assertTrue(s.installer.rollback("acme.snips", InstallScope.GLOBAL, null) is RollbackResult.Refused)
        assertTrue(s.installer.rollback("acme.other", InstallScope.GLOBAL, null) is RollbackResult.Refused)
        assertTrue(s.installer.rollback("easyide.a", InstallScope.GLOBAL, null) is RollbackResult.Refused)
        assertTrue(s.installer.rollback("acme.snips", InstallScope.ENVIRONMENT, null) is RollbackResult.Refused)
        assertEquals(before, s.state.read())
        assertEquals(emptyMap<String, String>(), s.inventory.retained.value)
    }

    @Test fun `an invalid, mislabelled or revoked previous version is refused without changes`() = runTest {
        val s = Setup(tmp.newFolder("files"), revoked = { id, v -> id == "acme.rev" && v == "1.0.0" })
        s.install("snips", "1.0.0", test = this@RollbackTest)
        s.install("snips", "1.1.0", test = this@RollbackTest)
        val v1 = File(s.idDir("acme.snips"), "1.0.0/package.json")
        v1.writeText("{")
        val before = s.state.read()
        assertTrue(s.installer.rollback("acme.snips", InstallScope.GLOBAL, null) is RollbackResult.Refused)
        v1.writeText(manifest("snips", "0.9.0")) // the dir name is not what is inside
        assertTrue(s.installer.rollback("acme.snips", InstallScope.GLOBAL, null) is RollbackResult.Refused)
        v1.writeText(manifest("snips", "1.0.0", """, "easyide": { "capabilities": ["sandbox.install"], "sandbox": { "install": [ { "id": "i", "title": "t", "run": "true" } ] } }"""))
        assertTrue(s.installer.rollback("acme.snips", InstallScope.GLOBAL, null) is RollbackResult.Refused)
        assertEquals("1.1.0", s.active().directory.name)
        assertEquals(before, s.state.read())

        s.install("rev", "1.0.0", test = this@RollbackTest)
        s.install("rev", "2.0.0", test = this@RollbackTest)
        val refused = s.installer.rollback("acme.rev", InstallScope.GLOBAL, null) as RollbackResult.Refused
        assertTrue(refused.problems.single().contains("revoked"))
        assertEquals("2.0.0", Files.readSymbolicLink(File(s.idDir("acme.rev"), "current").toPath()).toString())
    }

    @Test fun `state written before rollback existed still loads and needs re-approval to roll back`() = runTest {
        val s = Setup(tmp.newFolder("files"))
        val idDir = s.idDir("acme.run", "env1")
        File(idDir, "1.0.0").mkdirs()
        File(idDir, "1.0.0/package.json").writeText(manifest("run", "1.0.0", exec))
        File(idDir, "1.1.0").mkdirs()
        File(idDir, "1.1.0/package.json").writeText(manifest("run", "1.1.0", exec))
        Files.createSymbolicLink(File(idDir, "current").toPath(), Paths.get("1.1.0"))
        s.paths.extensionStateFile.writeText(
            """{"schemaVersion":1,"installs":[{"id":"acme.run","scope":"environment","envId":"env1","source":"SIDELOAD",""" +
                """"version":"1.1.0","installedAt":7,"approvedCapabilities":["sandbox.exec"]}],"crashDisabled":[]}""",
        )
        val old = s.state.read().installs.single()
        assertEquals(setOf("sandbox.exec"), old.approvedCapabilities)
        assertNull(old.previous)
        s.inventory.rescan()
        assertEquals("1.0.0", s.inventory.retained.value.values.single())

        val ask = s.installer.rollback("acme.run", InstallScope.ENVIRONMENT, "env1") as RollbackResult.NeedsApproval
        assertEquals(setOf("sandbox.exec"), ask.capabilities)
        assertEquals("1.1.0", s.active().directory.name) // nothing changed
        assertEquals(RollbackResult.Done("1.0.0"), s.installer.rollback("acme.run", InstallScope.ENVIRONMENT, "env1", ask.capabilities))
        val pkg = s.active()
        assertEquals(setOf("sandbox.exec"), pkg.approvedCapabilities)
        assertEquals(7L, pkg.installedAt)
        // The new field round-trips through a fresh store.
        assertEquals(RetainedVersion("1.1.0", setOf("sandbox.exec")), ExtensionStateStore(s.paths.extensionStateFile).read().installs.single().previous)
    }

    @Test fun `an interrupted flip leaves a safe state that rollback recovers from`() = runTest {
        val s = Setup(tmp.newFolder("files"))
        s.install("run", "1.0.0", exec, "env1", this@RollbackTest)
        s.install("run", "1.1.0", exec, "env1", this@RollbackTest)
        val idDir = s.idDir("acme.run", "env1")
        // Died before the rename: a stray `current.new` is replaced by the next flip.
        Files.createSymbolicLink(File(idDir, "current.new").toPath(), Paths.get("9.9.9"))
        // Died after the rename but before `state.json`: the active version runs with no approvals.
        Files.delete(File(idDir, "current").toPath())
        Files.createSymbolicLink(File(idDir, "current").toPath(), Paths.get("1.0.0"))
        s.inventory.rescan()
        assertEquals("1.0.0", s.active().directory.name)
        assertEquals(emptySet<String>(), s.active().approvedCapabilities)
        assertEquals(Source.SIDELOAD, s.active().source)

        // "Back" is the recorded version, with the approvals recorded for it.
        assertEquals(RollbackResult.Done("1.1.0"), s.installer.rollback("acme.run", InstallScope.ENVIRONMENT, "env1"))
        assertEquals("1.1.0", s.active().directory.name)
        assertEquals(setOf("sandbox.exec"), s.active().approvedCapabilities)
        assertTrue(!File(idDir, "current.new").exists() && !Files.isSymbolicLink(File(idDir, "current.new").toPath()))
    }
}
