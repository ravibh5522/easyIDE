package dev.easyide.extensions.manifest

import dev.easyide.extensions.Fixtures
import dev.easyide.extensions.Manifests
import dev.easyide.extensions.action.Action
import dev.easyide.extensions.action.OpenGroup
import dev.easyide.extensions.capability.Capability
import dev.easyide.extensions.contrib.BadgeKind
import dev.easyide.extensions.contrib.ContainerPlacement
import dev.easyide.extensions.contrib.NavigationTarget
import dev.easyide.extensions.contrib.OpenerPriorityName
import dev.easyide.extensions.contrib.UiScope
import dev.easyide.extensions.contrib.ViewContainerLocation
import dev.easyide.extensions.view.ActionTarget
import dev.easyide.extensions.view.ViewType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPointsTest {
    private fun fixture(name: String): PackageFiles =
        (PackageLayoutReader.read(Fixtures.dir(name), PackageLimits.DEFAULT) as PackageLayout.Ok).files

    private val docker: ExtensionDescriptor by lazy {
        when (val r = Manifests.parser.parse(fixture("ui-docker"))) {
            is ParseResult.Ok -> r.descriptor
            is ParseResult.Invalid -> error("ui-docker must validate: ${r.errors}")
        }
    }

    @Test fun `the docker fixture parses clean and fills every new point`() {
        val r = Manifests.parser.parse(fixture("ui-docker")) as ParseResult.Ok
        assertEquals(emptyList<Diagnostic>(), r.warnings)
        val c = docker.contributes
        val nav = c.navigation.single()
        assertEquals("acme.docker.nav", nav.id)
        assertEquals(NavigationTarget.Container("acme.docker.main"), nav.target)
        assertEquals(UiScope.WORKSPACE, nav.scope)
        assertEquals("runningCount", nav.badge?.path)
        assertEquals(BadgeKind.COUNT, nav.badge?.kind)
        val container = c.viewContainers.single()
        assertEquals(ViewContainerLocation.SIDEBAR, container.location)
        assertEquals(listOf(ContainerPlacement.SIDEBAR, ContainerPlacement.SECONDARY_SIDEBAR), container.locations)
        assertTrue(container.extended)
        assertEquals("containers", c.views.single().schema?.state?.keys?.first())
        val doc = c.documents.single()
        assertEquals("acme.docker/container", doc.type)
        assertEquals(5, doc.state?.intervalSec)
        assertEquals(OpenerPriorityName.OPTION, c.documentOpeners.single().priority)
        assertEquals(setOf(ContainerPlacement.SIDEBAR, ContainerPlacement.PANEL), c.layoutPresets.single().containers.keys)
        assertEquals(2, c.layoutPresets.single().stage?.groups)
        assertTrue(docker.capabilities.satisfies(Capability.UiContribute))
        assertEquals(InstallScope.ENVIRONMENT, docker.scope)
    }

    @Test fun `view files decode into typed trees with actions, args and confirm`() {
        val list = docker.contributes.views.single().schema!!.root.children[1]
        assertEquals(ViewType.LIST, list.type)
        val row = list.item!!
        assertEquals("ext://acme.docker/container/{id}", (row.action!!.target as ActionTarget.Open).uri.source)
        assertEquals(OpenGroup.ACTIVE, (row.action!!.target as ActionTarget.Open).group)
        val stop = row.children.first { it.type == ViewType.ICON_BUTTON && it.text("label")!!.source.startsWith("Stop") }
        assertEquals(ActionTarget.Command("acme.docker.stop"), stop.action!!.target)
        assertTrue(stop.action!!.confirm!!.destructive)
        assertNull(stop.action!!.into)
        assertEquals(ViewType.EMPTY_STATE, list.empty?.type)
    }

    @Test fun `old packs parse as before and leave every new point empty`() {
        val d = (Manifests.parser.parse(fixture("python")) as ParseResult.Ok).descriptor
        val c = d.contributes
        assertTrue(c.navigation.isEmpty() && c.documents.isEmpty() && c.documentOpeners.isEmpty() && c.layoutPresets.isEmpty() && c.viewBadges.isEmpty())
        assertTrue(!d.capabilities.satisfies(Capability.UiContribute))
    }

    @Test fun `a bare activitybar container and a schemaless view need no ui contribute`() {
        val legacy = Manifests.ok(Manifests.minimal("""
            "contributes": {
              "viewsContainers": { "activitybar": [{ "id": "demo.box", "title": "Box", "icon": "box" }] },
              "views": { "demo.box": [{ "id": "demo.list", "name": "List" }] } }"""))
        val container = legacy.contributes.viewContainers.single()
        assertEquals(ViewContainerLocation.ACTIVITY_BAR, container.location)
        assertEquals(false, container.extended)
        assertEquals(UiScope.WORKSPACE, container.scope)
        assertNull(legacy.contributes.views.single().schema)
    }

    // ---- capability and scope

    private fun errors(easyide: String = "", contributes: String = "", extra: Map<String, String> = emptyMap()): List<Diagnostic> {
        val body = buildString {
            if (contributes.isNotBlank()) append(""""contributes": { $contributes }, """)
            append(""""easyide": { $easyide }""")
        }
        return Manifests.errors(Manifests.minimal(body), extra)
    }

    private fun codes(easyide: String = "", contributes: String = "", extra: Map<String, String> = emptyMap()) =
        errors(easyide, contributes, extra).map { it.code }

    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="currentColor" d="M4 4h16v16H4z"/></svg>"""
    private val simpleView = """{ "viewSchema": 1, "root": { "type": "text", "value": "hi" } }"""
    private val container = """"viewsContainers": { "sidebar": [{ "id": "acme.demo.box", "title": "Box", "icon": "./i.svg" }] }"""
    private val files = mapOf("i.svg" to svg, "v.json" to simpleView)
    private val nav = """"navigation": [{ "id": "acme.demo.nav", "title": "Box", "icon": "./i.svg", "target": { "container": "acme.demo.box" } }]"""

    @Test fun `new points require ui contribute`() {
        val e = errors(""""capabilities": [], $nav""", container, files)
        assertEquals(listOf(DiagnosticCode.CAP_UNDECLARED), e.map { it.code })
        assertTrue(e.single().message.contains("ui.contribute"))
        assertEquals(emptyList<Diagnostic>(), Manifests.parse(Manifests.minimal(""""contributes": { $container }, "easyide": { "capabilities": ["ui.contribute"], $nav }"""), mapOf("i.svg" to svg)).let { (it as ParseResult.Ok).warnings })
    }

    @Test fun `ui contribute declared but unused warns`() {
        val w = Manifests.parse(Manifests.minimal(""""easyide": { "capabilities": ["ui.contribute"] }""")).warnings
        assertEquals(listOf(DiagnosticCode.CAP_UNUSED), w.map { it.code })
    }

    @Test fun `an inline action in a view adds its capability need and forces environment scope`() {
        val view = """{ "viewSchema": 1, "root": { "type": "button", "label": "Go", "action": { "type": "sandboxExec", "command": ["ls"], "output": "capture" } } }"""
        val c = """"views": { "acme.demo.box": [{ "id": "acme.demo.v", "name": "V", "schema": "./v.json" }] }, $container"""
        val e = errors(""""capabilities": ["ui.contribute"]""", c, files + ("v.json" to view))
        assertEquals(listOf(DiagnosticCode.CAP_UNDECLARED), e.map { it.code })
        assertTrue(e.single().message.contains("sandbox.exec"))
        val ok = Manifests.ok(Manifests.minimal(""""contributes": { $c }, "easyide": { "capabilities": ["ui.contribute", "sandbox.exec"] }"""), files + ("v.json" to view))
        assertEquals(InstallScope.ENVIRONMENT, ok.scope)
        val inline = ok.contributes.views.single().schema!!.root.action!!.target as ActionTarget.Inline
        assertTrue(inline.action is Action.SandboxExec)
    }

    // ---- ids, titles, references

    @Test fun `ids of the new points carry the extension id`() {
        val badNav = """"navigation": [{ "id": "demo.nav", "title": "Box", "icon": "./i.svg", "target": { "command": "demo.go" } }]"""
        assertEquals(listOf(DiagnosticCode.UI_ID), codes(""""capabilities": ["ui.contribute"], $badNav""", """"commands": [{ "command": "demo.go", "title": "Go" }]""", files))
        val badContainer = """"viewsContainers": { "sidebar": [{ "id": "box", "title": "Box", "icon": "./i.svg" }] }"""
        assertEquals(listOf(DiagnosticCode.UI_ID), codes(""""capabilities": ["ui.contribute"]""", badContainer, files))
        val badType = """"documents": [{ "type": "demo/note", "title": "N", "icon": "./i.svg", "schema": "./v.json" }]"""
        assertEquals(listOf(DiagnosticCode.UI_ID), codes(""""capabilities": ["ui.contribute"], $badType""", "", files))
    }

    @Test fun `navigation titles over 14 characters are refused`() {
        val long = nav.replace("\"Box\"", "\"Fifteen letters\"")
        assertEquals(listOf(DiagnosticCode.UI_TITLE), codes(""""capabilities": ["ui.contribute"], $long""", container, files))
    }

    @Test fun `navigation beyond three items keeps the first three with a warning`() {
        fun item(n: Int) = """{ "id": "acme.demo.nav$n", "title": "N$n", "icon": "./i.svg", "target": { "container": "acme.demo.box" } }"""
        val manifest = Manifests.minimal(""""contributes": { $container }, "easyide": { "capabilities": ["ui.contribute"], "navigation": [${(1..5).joinToString { item(it) }}] }""")
        val r = Manifests.parse(manifest, files) as ParseResult.Ok
        assertEquals(3, r.descriptor.contributes.navigation.size)
        assertTrue(r.warnings.any { it.code == DiagnosticCode.UI_IGNORED })
    }

    @Test fun `order below the reserved floor warns`() {
        val low = nav.replace("\"target\"", "\"order\": 5, \"target\"")
        val r = Manifests.parse(Manifests.minimal(""""contributes": { $container }, "easyide": { "capabilities": ["ui.contribute"], $low }"""), files) as ParseResult.Ok
        assertTrue(r.warnings.any { it.code == DiagnosticCode.UI_ORDER })
    }

    @Test fun `nav scope defaults to the scope of its container`() {
        val both = container.replace("\"icon\": \"./i.svg\"", "\"icon\": \"./i.svg\", \"scope\": \"both\"")
        val d = Manifests.ok(Manifests.minimal(""""contributes": { $both }, "easyide": { "capabilities": ["ui.contribute"], $nav }"""), files)
        assertEquals(UiScope.BOTH, d.contributes.navigation.single().scope)
    }

    @Test fun `references among the ui points must resolve`() {
        val noContainer = errors(""""capabilities": ["ui.contribute"], $nav""", "", files)
        assertEquals(listOf(DiagnosticCode.UI_REF), noContainer.map { it.code })
        assertEquals("/easyide/navigation/0/target", noContainer.single().pointer)
        val badge = nav.replace("\"target\"", "\"badge\": { \"view\": \"acme.demo.nope\", \"path\": \"n\" }, \"target\"")
        assertEquals(listOf(DiagnosticCode.UI_REF), codes(""""capabilities": ["ui.contribute"], $badge""", container, files))
        val viewBadge = """"navigation": [], "viewBadge": [{ "nav": "acme.demo.gone", "view": "acme.demo.v", "path": "n" }]"""
        assertTrue(codes(""""capabilities": ["ui.contribute"], $viewBadge""", container, files).all { it == DiagnosticCode.UI_REF })
        val opener = """"documentOpeners": [{ "glob": "**/*.md", "type": "acme.demo/note" }]"""
        assertEquals(listOf(DiagnosticCode.UI_REF), codes(""""capabilities": ["ui.contribute"], $opener""", "", files))
    }

    @Test fun `an opener cannot claim the builtin priority`() {
        val doc = """"documents": [{ "type": "acme.demo/note", "title": "N", "icon": "./i.svg", "schema": "./v.json" }]"""
        val opener = """"documentOpeners": [{ "glob": "**/*.md", "type": "acme.demo/note", "priority": "builtin" }]"""
        assertEquals(listOf(DiagnosticCode.UI_PRIORITY), codes(""""capabilities": ["ui.contribute"], $doc, $opener""", "", files))
    }

    @Test fun `an open target must be one of the packs own document types`() {
        val view = """{ "viewSchema": 1, "root": { "type": "row", "open": "ext://acme.demo/note/{id}", "children": [] } }"""
        val c = """"views": { "acme.demo.box": [{ "id": "acme.demo.v", "name": "V", "schema": "./v.json" }] }, $container"""
        val e = errors(""""capabilities": ["ui.contribute"]""", c, files + ("v.json" to view))
        assertEquals(listOf(DiagnosticCode.UI_REF), e.map { it.code })
        assertEquals("v.json", e.single().file)
        val foreign = view.replace("ext://acme.demo/", "ext://other.pack/")
        assertEquals(listOf(DiagnosticCode.VIEW_ACTION), errors(""""capabilities": ["ui.contribute"]""", c, files + ("v.json" to foreign)).map { it.code })
    }

    @Test fun `a view action naming an undeclared command of the pack is an error`() {
        val view = """{ "viewSchema": 1, "root": { "type": "button", "label": "Go", "action": "demo.missing" } }"""
        val c = """"views": { "acme.demo.box": [{ "id": "acme.demo.v", "name": "V", "schema": "./v.json" }] }, $container"""
        val e = errors(""""capabilities": ["ui.contribute"]""", c, files + ("v.json" to view))
        assertEquals(listOf(DiagnosticCode.COMMAND_UNRESOLVED), e.map { it.code })
        assertEquals("v.json", e.single().file)
    }

    // ---- limits

    @Test fun `more than ten documents or twenty schema views are refused`() {
        fun doc(n: Int) = """{ "type": "acme.demo/d$n", "title": "D", "icon": "./i.svg", "schema": "./v.json" }"""
        assertTrue(DiagnosticCode.UI_LIMIT in codes(""""capabilities": ["ui.contribute"], "documents": [${(1..11).joinToString { doc(it) }}]""", "", files))
        fun view(n: Int) = """{ "id": "acme.demo.v$n", "name": "V", "schema": "./v.json" }"""
        val many = """"views": { "acme.demo.box": [${(1..21).joinToString { view(it) }}] }, $container"""
        assertTrue(DiagnosticCode.UI_LIMIT in codes(""""capabilities": ["ui.contribute"]""", many, files))
    }

    @Test fun `more than ten containers presets or twenty openers are refused`() {
        fun box(n: Int) = """{ "id": "acme.demo.b$n", "title": "B", "icon": "./i.svg" }"""
        assertTrue(DiagnosticCode.UI_LIMIT in codes(""""capabilities": ["ui.contribute"]""", """"viewsContainers": { "sidebar": [${(1..11).joinToString { box(it) }}] }""", files))
        fun preset(n: Int) = """{ "id": "acme.demo.p$n", "title": "P" }"""
        assertTrue(DiagnosticCode.UI_LIMIT in codes(""""capabilities": ["ui.contribute"], "layoutPresets": [${(1..11).joinToString { preset(it) }}]""", "", files))
        val doc = """"documents": [{ "type": "acme.demo/note", "title": "N", "icon": "./i.svg", "schema": "./v.json" }]"""
        fun opener(n: Int) = """{ "glob": "**/*.$n", "type": "acme.demo/note" }"""
        assertTrue(DiagnosticCode.UI_LIMIT in codes(""""capabilities": ["ui.contribute"], $doc, "documentOpeners": [${(1..21).joinToString { opener(it) }}]""", "", files))
    }

    @Test fun `intervals below two seconds are a schema error`() {
        val doc = """"documents": [{ "type": "acme.demo/note", "title": "N", "icon": "./i.svg", "schema": "./v.json", "state": { "provider": "demo.s", "intervalSec": 1 } }]"""
        val e = errors(""""capabilities": ["ui.contribute"], $doc""", """"commands": [{ "command": "demo.s", "title": "S" }]""", files)
        assertEquals(DiagnosticCode.SCHEMA, e.single().code)
        assertEquals("/easyide/documents/0/state/intervalSec", e.single().pointer)
    }

    @Test fun `containers keep their placements and the schema view flag`() {
        val d = Manifests.ok(Manifests.minimal(""""contributes": { "viewsContainers": {
            "sidebar": [{ "id": "acme.demo.a", "title": "A", "icon": "./i.svg", "scope": "app" }],
            "secondarySidebar": [{ "id": "acme.demo.b", "title": "B", "icon": "./i.svg" }],
            "panel": [{ "id": "acme.demo.c", "title": "C", "icon": "./i.svg", "scope": "both" }] } },
            "easyide": { "capabilities": ["ui.contribute"] }"""), files)
        assertEquals(
            listOf(ContainerPlacement.SIDEBAR, ContainerPlacement.SECONDARY_SIDEBAR, ContainerPlacement.PANEL),
            d.contributes.viewContainers.map { it.location.placement }.sortedBy { it.ordinal },
        )
        assertEquals(UiScope.APP, d.contributes.viewContainers.first { it.id == "acme.demo.a" }.scope)
    }
}
