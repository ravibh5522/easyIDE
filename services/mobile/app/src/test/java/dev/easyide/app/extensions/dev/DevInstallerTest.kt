package dev.easyide.app.extensions.dev

import dev.easyide.app.extensions.install.DiskExtensionInventory
import dev.easyide.app.extensions.install.ExtensionStateStore
import dev.easyide.app.extensions.install.LocalInstaller
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.extensions.action.LogEntry
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DevInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun manifest(caps: List<String>, name: String = "run") =
        """{ "name": "$name", "publisher": "acme", "version": "0.1.0", "engines": { "easyide": "^0.3.0" },
           "easyide": { "capabilities": [${caps.joinToString { "\"$it\"" }}], "actions": { "acme.$name.go": { "type": "showMessage", "text": "hi" } } },
           "contributes": { "commands": [ { "command": "acme.$name.go", "title": "Go" } ] } }"""

    private inner class Setup(var devMode: Boolean = true) {
        val paths = SandboxPaths(tmp.newFolder("files")).also { it.ensureBaseDirs() }
        val state = ExtensionStateStore(paths.extensionStateFile)
        val inventory = DiskExtensionInventory(paths, { emptyList() }, state, Dispatchers.Unconfined)
        val local = LocalInstaller(
            paths, state, inventory, ManifestParser(ManifestSchema.validator, ParseOptions(builtInCommands = CommandIds.ALL)),
            { PackageLimits.DEFAULT }, Dispatchers.Unconfined,
        )
        val log = ArrayList<LogEntry>()
        val notes = ArrayList<String>()
        var now = 1_000L
        val dev = DevInstaller(local, { inventory.installed.value }, { devMode }, log::add, notes::add, Dispatchers.Unconfined, clock = { now })
        val inbox = tmp.newFolder("dev-inbox")

        fun push(caps: List<String>, id: String = "acme.run", name: String = "run") {
            ZipOutputStream(File(inbox, "$id.easyext").outputStream()).use { z ->
                z.putNextEntry(ZipEntry("package.json")); z.write(manifest(caps, name).toByteArray()); z.closeEntry()
            }
        }
    }

    @Test fun `developer mode off refuses and installs nothing`() = runTest {
        val s = Setup(devMode = false)
        s.push(listOf("clipboard"))
        assertTrue(s.dev.fromInbox(s.inbox, "acme.run") is DevOutcome.Refused)
        assertEquals(emptyList<Any>(), s.inventory.installed.value)
        assertTrue(s.log.single().message.contains("developer mode"))
    }

    @Test fun `first push waits for approval, identical reloads are silent, new capabilities prompt again`() = runTest {
        val s = Setup()
        s.push(listOf("clipboard"))
        val first = s.dev.fromInbox(s.inbox, "acme.run") as DevOutcome.Pending
        assertEquals(PromptReason.FIRST_INSTALL, first.pending.reason)
        assertEquals(first.pending, s.dev.pending.value)
        assertFalse("the inbox archive is consumed", File(s.inbox, "acme.run.easyext").exists())
        s.dev.approve(first.pending, null)
        assertNull(s.dev.pending.value)
        val v1 = s.inventory.installed.value.single()
        assertEquals(Source.DEV, v1.source)
        assertEquals("0.1.0-dev.1000", v1.directory.name)

        s.now = 2_000L
        s.push(listOf("clipboard"))
        assertEquals(DevOutcome.Installed("acme.run", "0.1.0-dev.2000"), s.dev.fromInbox(s.inbox, "acme.run"))
        val v2 = s.inventory.installed.value.single()
        assertEquals("0.1.0-dev.2000", v2.directory.name)
        assertEquals(Source.DEV, v2.source)
        assertTrue("previous kept for rollback", File(v2.directory.parentFile, "0.1.0-dev.1000").isDirectory)

        s.now = 2_000L // same clock: the version still advances
        s.push(listOf("clipboard", "ui.settings"))
        val changed = s.dev.fromInbox(s.inbox, "acme.run") as DevOutcome.Pending
        assertEquals(PromptReason.CAPABILITIES_CHANGED, changed.pending.reason)
        assertEquals(setOf("ui.settings"), changed.pending.added)
        assertEquals("0.1.0-dev.2001", changed.pending.pkg.descriptor.version.toString())
        s.dev.decline(changed.pending.pkg)
        assertNull(s.dev.pending.value)
        assertEquals("0.1.0-dev.2000", s.inventory.installed.value.single().directory.name)
        assertEquals(0, s.paths.extensionStagingDir.listFiles()!!.size)
    }

    @Test fun `an archive whose id is not the requested one is refused`() = runTest {
        val s = Setup()
        s.push(listOf("clipboard"), id = "acme.run", name = "other")
        assertTrue(s.dev.fromInbox(s.inbox, "acme.run") is DevOutcome.Refused)
        assertEquals(0, s.paths.extensionStagingDir.listFiles()!!.size)
        assertTrue(s.dev.fromInbox(s.inbox, "acme.missing") is DevOutcome.Refused)
    }

    @Test fun `a newer prompt replaces the waiting one`() = runTest {
        val s = Setup()
        s.push(listOf("clipboard"))
        val a = s.dev.fromInbox(s.inbox, "acme.run") as DevOutcome.Pending
        s.push(listOf("clipboard"))
        val b = s.dev.fromInbox(s.inbox, "acme.run") as DevOutcome.Pending
        assertEquals(b.pending, s.dev.pending.value)
        assertFalse(a.pending.pkg.directory.exists())
    }

    @Test fun `a local request installs the project folder without author files and is consumed`() = runTest {
        val s = Setup()
        val project = tmp.newFolder("project")
        val ext = File(project, "exts/run").apply { mkdirs() }
        File(ext, "package.json").writeText(manifest(listOf("clipboard")))
        File(ext, "test").mkdirs(); File(ext, "test/loads.json").writeText("{}")
        File(ext, ".easyide-ext.json").writeText("{}")
        File(ext, "dist").mkdirs(); File(ext, "dist/acme.run-0.1.0.easyext").writeText("PK")
        val request = File(project, ".easyide/dev/acme.run.json").apply { parentFile.mkdirs() }
        request.writeText("""{"id":"acme.run","folder":"exts/run","requestedAt":"2026-09-24T10:00:00Z"}""")
        val p = s.dev.fromRequest(request, project) as DevOutcome.Pending
        assertFalse(request.exists())
        assertEquals(listOf("package.json"), p.pending.pkg.directory.list()!!.toList())
        s.dev.approve(p.pending, null)
        s.now = 5_000L
        request.writeText("""{"id":"acme.run","folder":"exts/run","requestedAt":"2026-09-24T10:00:05Z"}""")
        assertEquals(DevOutcome.Installed("acme.run", "0.1.0-dev.5000"), s.dev.fromRequest(request, project))
    }

    @Test fun `a request escaping the project is refused and still consumed`() = runTest {
        val s = Setup()
        val project = tmp.newFolder("project")
        val request = File(project, ".easyide/dev/acme.run.json").apply { parentFile.mkdirs() }
        request.writeText("""{"id":"acme.run","folder":"../elsewhere"}""")
        assertTrue(s.dev.fromRequest(request, project) is DevOutcome.Refused)
        assertFalse(request.exists())
    }
}
