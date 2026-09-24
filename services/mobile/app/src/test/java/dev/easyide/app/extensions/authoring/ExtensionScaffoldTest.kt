package dev.easyide.app.extensions.authoring

import dev.easyide.app.extensions.install.DiskExtensionInventory
import dev.easyide.app.extensions.install.ExtensionStateStore
import dev.easyide.app.extensions.install.FileFolder
import dev.easyide.app.extensions.install.LocalInstaller
import dev.easyide.app.extensions.install.StageResult
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.extensions.authoring.ExtensionTemplates
import dev.easyide.extensions.manifest.ManifestParser
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.ParseOptions
import dev.easyide.extensions.schema.ManifestSchema
import dev.easyide.sandbox.SandboxPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExtensionScaffoldTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The same directory the `copyAuthoringTemplates` task packages as APK assets. */
    private val templates = File("../../shared/extension-templates/templates")
    private val read: (String, String) -> String? = { t, p -> File(templates, "$t/$p").takeIf { it.isFile }?.readText() }

    @Test fun `every in-app template is written, then installs from its folder`() = runTest {
        val paths = SandboxPaths(tmp.newFolder("files")).also { it.ensureBaseDirs() }
        val state = ExtensionStateStore(paths.extensionStateFile)
        val inventory = DiskExtensionInventory(paths, { emptyList() }, state, Dispatchers.Unconfined)
        val installer = LocalInstaller(
            paths, state, inventory, ManifestParser(ManifestSchema.validator, ParseOptions(builtInCommands = CommandIds.ALL)),
            { PackageLimits.DEFAULT }, Dispatchers.Unconfined,
        )
        for (t in ExtensionTemplates.DECLARATIVE) {
            val project = tmp.newFolder("p-$t")
            val r = ExtensionScaffold.create(t, "Acme", "my-$t", "", 2026, project, read) as ScaffoldResult.Created
            assertEquals("acme.my-$t", r.id)
            assertEquals(File(project, "my-$t"), r.dir)
            assertTrue(File(r.dir, ".gitignore").isFile)
            assertTrue(File(r.dir, "package.json").readText().contains("\"name\": \"my-$t\""))
            val staged = installer.stageFolder(FileFolder.of(r.dir))
            assertTrue("$t: $staged", staged is StageResult.Staged)
            val pkg = (staged as StageResult.Staged).pkg
            assertEquals("acme.my-$t", pkg.descriptor.id.value)
            // Author-side files stay out of the package, as `easyide-ext package` leaves them out.
            assertTrue(pkg.directory.walkTopDown().none { it.name == ".gitignore" || it.name == ".easyide-ext.json" || it.name == "test" })
            installer.discard(pkg)
        }
    }

    @Test fun `refusals`() {
        val project = tmp.newFolder("p")
        assertEquals(ScaffoldRefusal.UNKNOWN_TEMPLATE, (ExtensionScaffold.create("wasm-rust", "acme", "x", null, 2026, project, read) as ScaffoldResult.Refused).reason)
        assertEquals(ScaffoldRefusal.INVALID_ID, (ExtensionScaffold.create("theme", "acme", "../x", null, 2026, project, read) as ScaffoldResult.Refused).reason)
        File(project, "taken/keep.txt").apply { parentFile.mkdirs(); writeText("mine") }
        assertEquals(ScaffoldRefusal.EXISTS, (ExtensionScaffold.create("theme", "acme", "taken", null, 2026, project, read) as ScaffoldResult.Refused).reason)
        assertEquals("mine", File(project, "taken/keep.txt").readText())
        assertEquals(ScaffoldRefusal.DAMAGED_TEMPLATE, (ExtensionScaffold.create("theme", "acme", "y", null, 2026, project) { _, _ -> null } as ScaffoldResult.Refused).reason)
        // An existing empty folder is fine.
        File(project, "empty").mkdirs()
        assertTrue(ExtensionScaffold.create("snippets", "acme", "empty", null, 2026, project, read) is ScaffoldResult.Created)
    }
}
