package dev.easyide.extensions.contrib

import dev.easyide.extensions.Fixtures
import dev.easyide.extensions.Manifests
import dev.easyide.extensions.manifest.DiagnosticCode
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.PackageLayout
import dev.easyide.extensions.manifest.PackageLayoutReader
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.ParseResult
import dev.easyide.extensions.manifest.SemVer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UiContributionRegistryTest {
    private val docker: ExtensionDescriptor by lazy {
        val files = (PackageLayoutReader.read(Fixtures.dir("ui-docker"), PackageLimits.DEFAULT) as PackageLayout.Ok).files
        (Manifests.parser.parse(files) as ParseResult.Ok).descriptor
    }

    private val other = ExtensionId.parse("acme.other")!!
    private fun registered(d: ExtensionDescriptor, granted: Boolean = true) = RegisteredExtension(d.id, d.version, d.contributes, granted)

    @Test fun `the shell points of an enabled extension fill their typed stores with the owner`() {
        val reg = ContributionRegistry()
        reg.update(listOf(registered(docker)))
        val owner = Owner.Ext(docker.id)
        assertEquals(listOf("acme.docker.nav"), reg.navigation.entries.value.map { it.value.id })
        assertEquals(owner, reg.navigation.entries.value.single().owner)
        assertEquals(ContributionRef(ContributionRef.Kind.NAVIGATION, null, "acme.docker.nav"), reg.navigation.entries.value.single().ref)
        assertEquals(listOf("acme.docker/container"), reg.documents.entries.value.map { it.value.type })
        assertEquals(listOf("acme.docker.ops"), reg.layoutPresets.entries.value.map { it.value.id })
        assertEquals(1, reg.documentOpeners.entries.value.size)
        assertEquals(listOf("acme.docker.main"), reg.viewContainers.entries.value.map { it.value.id })
        assertTrue(reg.views.entries.value.single().value.schema != null)
    }

    @Test fun `without the ui contribute grant only the older shapes remain`() {
        val legacy = Manifests.ok(Manifests.minimal("""
            "contributes": {
              "viewsContainers": { "activitybar": [{ "id": "demo.box", "title": "Box", "icon": "box" }] },
              "views": { "demo.box": [{ "id": "demo.list", "name": "List" }] } }"""), extra = emptyMap())
        val reg = ContributionRegistry()
        reg.update(listOf(registered(docker, granted = false), registered(legacy, granted = false)))
        assertTrue(reg.navigation.entries.value.isEmpty())
        assertTrue(reg.documents.entries.value.isEmpty())
        assertTrue(reg.layoutPresets.entries.value.isEmpty())
        assertTrue(reg.documentOpeners.entries.value.isEmpty())
        // The docker pack's shell container and schema view are gone; the legacy pack's bare container and view stay.
        assertEquals(listOf("demo.box"), reg.viewContainers.entries.value.map { it.value.id })
        assertEquals(listOf("demo.list"), reg.views.entries.value.map { it.value.id })
    }

    @Test fun `a duplicate navigation id or document type keeps the earlier owner`() {
        val clash = docker.contributes.copy(
            navigation = docker.contributes.navigation.map { it.copy(title = "Other") },
        )
        val reg = ContributionRegistry()
        reg.update(listOf(RegisteredExtension(docker.id, docker.version, docker.contributes), RegisteredExtension(other, SemVer(1, 0, 0), clash)))
        assertEquals(listOf("Docker"), reg.navigation.entries.value.map { it.value.title })
        val c = reg.snapshot.value.conflicts.filter { it.ref.kind == ContributionRef.Kind.NAVIGATION }
        assertEquals(Owner.Ext(docker.id), c.single().winner)
        assertEquals(Owner.Ext(other), c.single().loser)
        assertEquals(DiagnosticCode.CONTRIBUTION_SHADOWED, c.single().code)
    }

    @Test fun `removing the owner empties the shell stores and each store emits only when it changed`() = runTest(UnconfinedTestDispatcher()) {
        val reg = ContributionRegistry()
        val navs = ArrayList<Int>()
        val commands = ArrayList<Int>()
        val jobs = listOf(
            launch { reg.navigation.entries.collect { navs += it.size } },
            launch { reg.commands.entries.collect { commands += it.size } },
        )
        reg.update(listOf(registered(docker)))
        reg.update(listOf(registered(docker), RegisteredExtension(other, SemVer(1, 0, 0), Contributions(themes = emptyList()))))
        reg.remove(docker.id)
        assertEquals(listOf(0, 1, 0), navs)
        assertEquals(listOf(0, 4, 0), commands)
        jobs.forEach { it.cancel() }
    }
}
