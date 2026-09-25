package dev.easyide.app.ui.shell.ext

import dev.easyide.app.extensions.ExtFixtures
import dev.easyide.app.extensions.adapters.ShellContributions
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.DocumentType
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.NavEnv
import dev.easyide.app.ui.shell.NavPrefs
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.RejectReason
import dev.easyide.app.ui.shell.ShellLimits
import dev.easyide.app.ui.shell.ShellScope
import dev.easyide.app.ui.shell.host.AppDocuments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Extension contributions folded into the engine registries: what registers, what the engine refuses, and that core stays first. */
class ExtRegistriesTest {
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="currentColor" d="M4 4h16v16H4z"/></svg>"""
    private val view = """{ "viewSchema": 1, "root": { "type": "text", "value": "hi" } }"""

    private fun pack(nav: String = "", extra: String = "") = ExtFixtures.descriptor(ExtFixtures.manifest("""
        "contributes": { "viewsContainers": { "sidebar": [{ "id": "acme.demo.main", "title": "Docker", "icon": "./i.svg", "scope": "both" }] } },
        "easyide": { "capabilities": ["ui.contribute"],
          "navigation": [$nav],
          "documents": [{ "type": "acme.demo/box", "title": "{name}", "icon": "box", "schema": "./v.json" }],
          "documentOpeners": [{ "glob": "**/*.box", "type": "acme.demo/box", "priority": "default" }] $extra }
    """), mapOf("i.svg" to svg, "v.json" to view))

    private val nav = """{ "id": "acme.demo.nav", "title": "Docker", "icon": "box", "target": { "container": "acme.demo.main" }, "order": 5 }"""
    private val base = AppDocuments.registries { "text" }

    private fun fold(d: dev.easyide.extensions.manifest.ExtensionDescriptor) = ExtRegistries.fold(base, ShellContributions.of(ExtFixtures.snapshot(d)))

    @Test fun `containers and document types register with the pack as origin`() {
        val folded = fold(pack(nav))
        assertEquals(emptyList<Any>(), folded.rejections)
        val r = folded.registries
        assertEquals("acme.demo", r.containers.packOf("acme.demo.main"))
        assertEquals(Placement.SIDEBAR, r.containers.byId("acme.demo.main")?.placement)
        assertEquals("acme.demo/box", r.documents.resolve(DocumentUri.parse("ext://acme.demo/box/n1")!!).id)
        // The opener claims matching files by default.
        assertEquals("acme.demo/box", r.documents.resolve(DocumentUri.parse("file:///workspace/a.box")!!).id)
        // Core is untouched.
        assertNotNull(r.containers.byId(CoreShell.EXPLORER))
        assertEquals("easyide.settings", r.documents.resolve(DocumentUri.parse("easyide://settings/editor")!!).id)
    }

    @Test fun `navigation items are raised above the built-ins and never displace one`() {
        val contributions = ShellContributions.of(ExtFixtures.snapshot(pack(nav))).navigation
        val registered = ExtRegistries.navigation(base.navigation, contributions)
        assertEquals(emptyList<Any>(), registered.rejections)
        val item = registered.registry.byId("acme.demo.nav")!!
        assertEquals(ShellLimits.EXTENSION_ORDER_FLOOR, item.order)
        val visible = registered.registry.visible(ShellScope.APP, NavPrefs(), NavEnv({ true }, { true }))
        assertEquals(listOf(CoreShell.HOME, CoreShell.EXTENSIONS, CoreShell.SETTINGS, "acme.demo.nav"), visible.map { it.id })
        assertNull(base.navigation.byId("acme.demo.nav"))
    }

    @Test fun `an item that takes a core id is refused and logged, not registered`() {
        val stolen = """{ "id": "acme.demo.nav", "title": "Docker", "icon": "box", "target": { "container": "acme.demo.main" } }"""
        val contributions = ShellContributions.of(ExtFixtures.snapshot(pack(stolen))).navigation.map { c -> c.copy(item = c.item.copy(id = CoreShell.SETTINGS)) }
        val registered = ExtRegistries.navigation(base.navigation, contributions)
        assertEquals(RejectReason.DUPLICATE_ID, registered.rejections.single().reason)
        assertEquals("Settings", registered.registry.byId(CoreShell.SETTINGS)?.title)
    }

    @Test fun `more than three navigation items are capped by the engine`() {
        val items = (1..3).joinToString(",") { """{ "id": "acme.demo.n$it", "title": "N$it", "icon": "box", "target": { "container": "acme.demo.main" } }""" }
        val contributions = ShellContributions.of(ExtFixtures.snapshot(pack(items))).navigation
        assertEquals(3, contributions.size)
        val extra = contributions + contributions.first().let { it.copy(item = it.item.copy(id = "acme.demo.n4")) }
        val registered = ExtRegistries.navigation(base.navigation, extra)
        assertEquals(listOf(RejectReason.TOO_MANY), registered.rejections.map { it.reason })
    }

    @Test fun `a second pack cannot take an id the first holds`() {
        val first = ShellContributions.of(ExtFixtures.snapshot(pack(nav)))
        val dup = first.copy(containers = first.containers + first.containers.map { it.copy(extensionId = "acme.other") })
        val folded = ExtRegistries.fold(base, dup)
        assertTrue(folded.rejections.any { it.reason == RejectReason.DUPLICATE_ID || it.reason == RejectReason.NOT_NAMESPACED })
        assertEquals("acme.demo", folded.registries.containers.packOf("acme.demo.main"))
    }

    @Test fun `an unregistered document type resolves to the unavailable placeholder`() {
        val uri = DocumentUri.parse("ext://acme.gone/box/n1")!!
        assertEquals(DocumentType.UNAVAILABLE_ID, base.documents.resolve(uri).id)
    }
}
