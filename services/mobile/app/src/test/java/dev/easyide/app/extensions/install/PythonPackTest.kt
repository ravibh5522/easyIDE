package dev.easyide.app.extensions.install

import dev.easyide.app.extensions.BuiltInPackFixtures
import dev.easyide.app.extensions.ExtFixtures
import dev.easyide.app.extensions.adapters.ContributedServers
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.extensions.host.EnabledExtension
import dev.easyide.extensions.host.EnabledSet
import dev.easyide.extensions.host.Enablement
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
import dev.easyide.sandbox.SandboxPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * M3 exit (arch.md sec 12): the first-party Python pack, removed from app code, installs as
 * an `.easyext` and behaves as the built-in copy - same parsed contributions and actions,
 * same language servers - while its setup steps are staged for review rather than run.
 */
class PythonPackTest {

    @get:Rule val tmp = TemporaryFolder()

    private val parser = ManifestParser(ManifestSchema.validator, ParseOptions(builtInCommands = CommandIds.ALL))
    private val builtIn = BuiltInPackFixtures.load(ID).descriptor

    private fun easyext(dir: File): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                z.putNextEntry(ZipEntry(f.relativeTo(dir).invariantSeparatorsPath))
                z.write(f.readBytes())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private suspend fun installAsEasyext(envId: String): Pair<InstalledPackage, ExtensionDescriptor> {
        val paths = SandboxPaths(tmp.newFolder("files")).also { it.ensureBaseDirs() }
        val state = ExtensionStateStore(paths.extensionStateFile)
        val inventory = DiskExtensionInventory(paths, { emptyList() }, state, Dispatchers.Unconfined)
        val installer = LocalInstaller(paths, state, inventory, parser, { PackageLimits.DEFAULT }, Dispatchers.Unconfined)
        val bytes = easyext(File(BuiltInPackFixtures.root, ID))
        val staged = installer.stageArchive { bytes.inputStream() }
        assertTrue("$staged", staged is StageResult.Staged)
        installer.commit((staged as StageResult.Staged).pkg, envId)
        val pkg = inventory.installed.value.single()
        val files = (PackageLayoutReader.read(pkg.directory, PackageLimits.DEFAULT) as PackageLayout.Ok).files
        return pkg to (parser.parse(files) as ParseResult.Ok).descriptor
    }

    /** Everything the runtime acts on, with the host directory factored out. */
    private fun behaviour(d: ExtensionDescriptor): String = d.toString().replace(d.root, "<root>")

    @Test fun `the pack installed as an easyext is the built-in pack`() = runTest {
        val (pkg, installed) = installAsEasyext(ENV)
        assertEquals(behaviour(builtIn), behaviour(installed))
        assertEquals(InstallScope.ENVIRONMENT, pkg.scope)
        assertEquals(ENV, pkg.envId)
        assertEquals(installed.capabilities.items.map { it.id }.toSet(), pkg.approvedCapabilities)
        assertTrue(installed.contributes.sandbox!!.install.isNotEmpty())
    }

    @Test fun `built-in and installed copies declare the same servers where each applies`() = runTest {
        val (pkg, installed) = installAsEasyext(ENV)
        val snapshot = ExtFixtures.snapshot(builtIn)
        val config = mapOf<String, JsonElement>("python.interpreter" to JsonPrimitive("python3"))
        val ubuntu = ExtFixtures.context("envDistro" to "\"ubuntu\"")
        fun servers(env: String, p: InstalledPackage, d: ExtensionDescriptor) = ContributedServers.declarations(
            env, snapshot.languageServers, snapshot.sandbox,
            EnabledSet(1, listOf(EnabledExtension(d, Enablement.granted(p, d), p))), config::get, ubuntu,
        ).declarations

        val bundled = InstalledPackage(
            directory = File(builtIn.root), scope = InstallScope.GLOBAL, envId = null, source = Source.BUILT_IN,
            installedAt = 0, approvedCapabilities = emptySet(), revoked = false, crashDisabled = false,
        )
        // Built-ins ship in the APK, so their servers exist in every environment.
        for (env in listOf(ENV, OTHER_ENV)) assertEquals(SERVERS, servers(env, bundled, builtIn).map { it.key.toString() })
        // An installed copy serves only the environment it was installed into.
        assertEquals(SERVERS, servers(ENV, pkg, installed).map { it.key.toString() })
        assertEquals(emptyList<String>(), servers(OTHER_ENV, pkg, installed).map { it.key.toString() })
        // Both carry the same setup recipe, run later in a visible terminal.
        val recipe = servers(ENV, bundled, builtIn).first().install!!
        assertEquals(recipe, servers(ENV, pkg, installed).first().install)
        assertEquals(listOf("apt-get update && apt-get install -y pipx nodejs npm", "npm install -g pyright && pipx install ruff && pipx ensurepath"), recipe.steps.map { it.run })
    }

    @Test fun `python files resolve to the pack, not to a bundled grammar`() {
        val index = File("src/main/assets/grammars/index.json").readText()
        assertTrue("source.python must not be in the core grammar index", "\"source.python\"" !in index)
        val langs = builtIn.contributes.languages.single()
        assertTrue(listOf(".py", ".pyi", ".pyw").all { it in langs.extensions })
        assertTrue("BUILD.bazel" in langs.filenames)
    }

    private companion object {
        const val ID = "easyide.python"
        const val ENV = "env1"
        const val OTHER_ENV = "env2"
        val SERVERS = listOf("easyide.python/pyright", "easyide.python/ruff")
    }
}
