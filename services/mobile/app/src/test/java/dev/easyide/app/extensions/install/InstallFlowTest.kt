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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InstallFlowTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun manifest(name: String, version: String = "1.0.0", easyide: String = "") =
        """{ "name": "$name", "publisher": "acme", "version": "$version", "engines": { "easyide": "^0.3.0" }$easyide }"""

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    private class Setup(root: File, builtInDirs: List<File> = emptyList()) {
        val paths = SandboxPaths(root).also { it.ensureBaseDirs() }
        val state = ExtensionStateStore(paths.extensionStateFile)
        val inventory = DiskExtensionInventory(paths, { builtInDirs }, state, Dispatchers.Unconfined)
        val installer = LocalInstaller(
            paths, state, inventory,
            ManifestParser(ManifestSchema.validator, ParseOptions(builtInCommands = CommandIds.ALL)),
            { PackageLimits.DEFAULT }, Dispatchers.Unconfined, clock = { 42L },
        )
    }

    @Test fun `archive install lands in a version dir behind current and records the approval`() = runTest {
        val s = Setup(tmp.newFolder("files"))
        val staged = s.installer.stageArchive { ByteArrayInputStream(zip("package.json" to manifest("snips"))) } as StageResult.Staged
        assertFalse(staged.pkg.alreadyInstalled)
        s.installer.commit(staged.pkg, envId = null)
        val pkg = s.inventory.installed.value.single()
        assertEquals("1.0.0", pkg.directory.name)
        assertEquals(Source.SIDELOAD, pkg.source)
        assertEquals(42L, pkg.installedAt)
        assertTrue(Files.isSymbolicLink(File(pkg.directory.parentFile, "current").toPath()))
        assertEquals(InstallScope.GLOBAL, s.state.read().installs.single().scope)
        s.installer.uninstall(pkg)
        assertEquals(emptyList<Any>(), s.inventory.installed.value)
        assertEquals(emptyList<Any>(), s.state.read().installs)
    }

    @Test fun `environment packs go into the environment's dir and keep their install time on update`() = runTest {
        val s = Setup(tmp.newFolder("files"))
        val cap = """, "easyide": { "capabilities": ["sandbox.exec"], "actions": { "acme.run.go": { "type": "runInTerminal", "command": "ls" } } }, "contributes": { "commands": [ { "command": "acme.run.go", "title": "Go" } ] }"""
        val v1 = s.installer.stageArchive { ByteArrayInputStream(zip("package.json" to manifest("run", "1.0.0", cap))) } as StageResult.Staged
        s.installer.commit(v1.pkg, envId = "env1")
        val v2 = s.installer.stageArchive { ByteArrayInputStream(zip("package.json" to manifest("run", "1.1.0", cap))) } as StageResult.Staged
        s.installer.commit(v2.pkg, envId = "env1")
        val pkg = s.inventory.installed.value.single()
        assertEquals("env1", pkg.envId)
        assertEquals("1.1.0", pkg.directory.name)
        assertEquals(setOf("sandbox.exec"), pkg.approvedCapabilities)
        assertTrue(File(pkg.directory.parentFile, "1.0.0").isDirectory) // previous version retained
    }

    @Test fun `zip slip, symlinks, sandbox install steps and bad manifests are refused`() = runTest {
        val s = Setup(tmp.newFolder("files"))
        val slip = s.installer.stageArchive { ByteArrayInputStream(zip("../evil" to "x", "package.json" to manifest("a"))) }
        assertTrue(slip is StageResult.Rejected)
        val bad = s.installer.stageArchive { ByteArrayInputStream(zip("package.json" to "{")) }
        assertTrue(bad is StageResult.Rejected)
        val steps = """, "easyide": { "capabilities": ["sandbox.install"], "sandbox": { "install": [ { "id": "i", "title": "t", "run": "true" } ] } }"""
        val sandbox = s.installer.stageArchive { ByteArrayInputStream(zip("package.json" to manifest("tool", easyide = steps))) }
        assertTrue(sandbox is StageResult.Rejected)
        val symlink = zip("package.json" to manifest("ln"), "link" to "target").also(::markSymlink)
        assertTrue(s.installer.stageArchive { ByteArrayInputStream(symlink) } is StageResult.Rejected)
        assertEquals(0, s.paths.extensionStagingDir.listFiles()!!.size)
    }

    @Test fun `folder copies obey the same rules`() = runTest {
        val s = Setup(tmp.newFolder("files"))
        val folder = Node("pkg", listOf(Node("package.json", content = manifest("folder")), Node("snippets", listOf(Node("a.json", content = "{}")))))
        val staged = s.installer.stageFolder(folder)
        assertTrue(staged is StageResult.Staged)
        val bad = Node("pkg", listOf(Node("package.json", content = manifest("f2")), Node("..", content = "x")))
        assertTrue(s.installer.stageFolder(bad) is StageResult.Rejected)
    }

    @Test fun `unrecorded or unapproved dirs are listed as unapproved sideloads`() = runTest {
        val s = Setup(tmp.newFolder("files"))
        val idDir = File(s.paths.globalExtensionsDir, "acme.manual").also { File(it, "2.0.0").mkdirs() }
        Files.createSymbolicLink(File(idDir, "current").toPath(), File("2.0.0").toPath())
        s.inventory.rescan()
        val pkg = s.inventory.installed.value.single()
        assertEquals(Source.SIDELOAD, pkg.source)
        assertEquals(emptySet<String>(), pkg.approvedCapabilities)
        s.inventory.setCrashDisabled(dev.easyide.extensions.manifest.ExtensionId.parse("acme.manual")!!, true)
        assertTrue(s.inventory.installed.value.single().crashDisabled)
    }

    @Test fun `built-ins unpack once per stamp and drop stale copies`() {
        val assets = object : AssetTree {
            val files = mapOf("extensions/easyide.a/package.json" to "{}", "extensions/easyide.a/s/x.json" to "[]")
            override fun list(path: String) = files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.distinct()
            override fun open(path: String): InputStream = ByteArrayInputStream(files.getValue(path).toByteArray())
        }
        val root = tmp.newFolder("builtin")
        File(root, "easyide.gone/1").mkdirs()
        val dirs = BuiltInExtensions(assets, root, "7").directories()
        assertEquals(listOf("7"), dirs.map { it.name })
        assertEquals("[]", File(dirs.single(), "s/x.json").readText())
        assertFalse(File(root, "easyide.gone").exists())
        assertEquals(listOf("8"), BuiltInExtensions(assets, root, "8").directories().map { it.name })
        assertEquals(listOf("8"), File(root, "easyide.a").list()!!.toList())
    }

    private class Node(override val name: String, private val kids: List<Node>? = null, private val content: String = "") : FolderNode {
        override val isDirectory: Boolean get() = kids != null
        override fun children(): List<FolderNode> = kids.orEmpty()
        override fun open(): InputStream = ByteArrayInputStream(content.toByteArray())
    }

    /** Sets "made by Unix" and S_IFLNK on the last central directory entry, as `zip -y` writes. */
    private fun markSymlink(bytes: ByteArray) {
        var p = bytes.size - 22
        while (p >= 0 && !(bytes[p] == 0x50.toByte() && bytes[p + 1] == 0x4b.toByte() && bytes[p + 2] == 0x05.toByte() && bytes[p + 3] == 0x06.toByte())) p--
        var cd = (bytes[p + 16].toInt() and 0xFF) or ((bytes[p + 17].toInt() and 0xFF) shl 8) or ((bytes[p + 18].toInt() and 0xFF) shl 16)
        var last = cd
        while (bytes[cd] == 0x50.toByte() && bytes[cd + 1] == 0x4b.toByte() && bytes[cd + 2] == 0x01.toByte()) {
            last = cd
            val n = (bytes[cd + 28].toInt() and 0xFF) or ((bytes[cd + 29].toInt() and 0xFF) shl 8)
            val e = (bytes[cd + 30].toInt() and 0xFF) or ((bytes[cd + 31].toInt() and 0xFF) shl 8)
            val c = (bytes[cd + 32].toInt() and 0xFF) or ((bytes[cd + 33].toInt() and 0xFF) shl 8)
            cd += 46 + n + e + c
        }
        bytes[last + 5] = 3
        bytes[last + 40] = 0xFF.toByte() // mode bits 0o120777 in the high 16 bits
        bytes[last + 41] = 0xA1.toByte()
    }
}
