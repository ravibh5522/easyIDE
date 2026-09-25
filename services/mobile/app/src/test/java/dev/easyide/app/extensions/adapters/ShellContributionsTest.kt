package dev.easyide.app.extensions.adapters

import dev.easyide.app.extensions.ExtFixtures
import dev.easyide.app.ui.screens.workspace.layout.PaneArrangement
import dev.easyide.app.ui.shell.IconRef
import dev.easyide.app.ui.shell.NavTarget
import dev.easyide.app.ui.shell.OpenerPriority
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.ScopeFilter
import dev.easyide.app.ui.shell.SplitAxis
import dev.easyide.app.ui.shell.UriPattern
import dev.easyide.extensions.contrib.BadgeKind
import dev.easyide.extensions.manifest.ExtensionDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellContributionsTest {
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="currentColor" d="M4 4h16v16H4z"/></svg>"""
    private val view = """{ "viewSchema": 1, "state": { "n": 0 }, "root": { "type": "text", "value": "hi" } }"""

    private val docker: ExtensionDescriptor = ExtFixtures.descriptor(ExtFixtures.manifest("""
        "contributes": {
          "commands": [{ "command": "acme.demo.open", "title": "Open" }, { "command": "acme.demo.state", "title": "State" }],
          "viewsContainers": {
            "sidebar": [{ "id": "acme.demo.main", "title": "Docker", "icon": "./icon.svg", "scope": "both", "locations": ["sidebar", "secondarySidebar"] }],
            "panel": [{ "id": "acme.demo.logs", "title": "Logs", "icon": "logs", "scope": "workspace" }] },
          "views": { "acme.demo.main": [{ "id": "acme.demo.list", "name": "List", "schema": "./v.json" }] } },
        "easyide": {
          "capabilities": ["ui.contribute", "ui.stage", "sandbox.exec"],
          "actions": { "acme.demo.state": { "type": "sandboxExec", "command": ["echo"], "output": "capture" },
                       "acme.demo.open": { "type": "sandboxExec", "command": ["echo"], "output": "capture" } },
          "navigation": [
            { "id": "acme.demo.nav", "title": "Docker", "icon": "./icon.svg", "target": { "container": "acme.demo.main" }, "order": 320,
              "when": "config.acme.demo.host != ''", "badge": { "view": "acme.demo.list", "path": "running", "kind": "dot" } },
            { "id": "acme.demo.cmd", "title": "Open", "icon": "play", "target": { "command": "acme.demo.open" }, "scope": "app" }],
          "documents": [{ "type": "acme.demo/box", "title": "{name}", "icon": "box", "schema": "./v.json", "multiple": true, "supportsSplit": false,
                          "state": { "provider": "acme.demo.state", "intervalSec": 5 } }],
          "documentOpeners": [{ "glob": "**/*.box", "type": "acme.demo/box", "priority": "default" }, { "glob": "**/*.bx", "type": "acme.demo/box" }],
          "layoutPresets": [{ "id": "acme.demo.wide", "title": "Wide", "sizeClass": ["expanded", "compact"],
                              "containers": { "sidebar": "acme.demo.main", "panel": "terminal" }, "panels": { "sidebar": true, "panel": false },
                              "stage": { "split": "column", "groups": 2 } }],
          "viewData": { "acme.demo.list": { "kind": "object", "from": { "type": "sandboxExec", "command": ["echo"], "output": "capture" }, "intervalSec": 3 } } }
    """), mapOf("icon.svg" to svg, "v.json" to view))

    private val legacy: ExtensionDescriptor = ExtFixtures.descriptor(ExtFixtures.manifest("""
        "contributes": {
          "viewsContainers": { "activitybar": [{ "id": "old.box", "title": "A very long legacy title", "icon": "box" }] },
          "views": { "old.box": [{ "id": "old.list", "name": "List" }, { "id": "old.other", "name": "Other" }] } },
        "easyide": { "viewData": { "old.list": { "kind": "list", "from": { "type": "sandboxExec", "command": ["ls"], "output": "capture" } } },
                     "capabilities": ["ui.stage", "sandbox.exec"] }
    """, name = "old"))

    private val shell = ShellContributions.of(ExtFixtures.snapshot(docker, legacy))

    @Test fun `containers keep their id, placement, scope and allowed locations`() {
        val main = shell.containers.first { it.spec.id == "acme.demo.main" }
        assertEquals("acme.demo", main.extensionId)
        assertEquals(Placement.SIDEBAR, main.spec.placement)
        assertEquals(ScopeFilter.BOTH, main.spec.scope)
        assertEquals(setOf(Placement.SIDEBAR, Placement.SECONDARY_SIDEBAR), main.spec.allowed)
        assertEquals(IconRef("ext:/host/ext/icon.svg"), main.spec.icon)
        val logs = shell.containers.first { it.spec.id == "acme.demo.logs" }
        assertEquals(Placement.PANEL, logs.spec.placement)
        assertEquals(IconRef("logs"), logs.spec.icon)
        assertEquals(Placement.entries.toSet(), logs.spec.allowed)
    }

    @Test fun `navigation items become shell items with a raw clause, a badge and either target`() {
        val nav = shell.navigation.first { it.item.id == "acme.demo.nav" }.item
        assertEquals(NavTarget.Container("acme.demo.main"), nav.target)
        assertEquals(320, nav.order)
        assertEquals("config.acme.demo.host != ''", nav.condition)
        assertEquals("acme.demo.list", nav.badge?.view)
        assertEquals("running", nav.badge?.path)
        val cmd = shell.navigation.first { it.item.id == "acme.demo.cmd" }.item
        assertEquals(NavTarget.Command("acme.demo.open"), cmd.target)
        assertEquals(ScopeFilter.APP, cmd.scope)
        assertEquals(setOf("acme.demo.open"), shell.commands)
        assertEquals(BadgeKind.DOT, shell.badges.single { it.navId == "acme.demo.nav" }.kind)
    }

    @Test fun `a bare activitybar container becomes a sidebar container with a navigation item`() {
        val c = shell.containers.first { it.extensionId == "acme.old" }.spec
        assertEquals("acme.old.old.box", c.id)
        assertEquals(Placement.SIDEBAR, c.placement)
        assertEquals(ScopeFilter.WORKSPACE, c.scope)
        val nav = shell.navigation.first { it.extensionId == "acme.old" }.item
        assertEquals("acme.old.old.box.nav", nav.id)
        assertEquals(NavTarget.Container("acme.old.old.box"), nav.target)
        assertTrue(nav.title.length <= 14)
        assertEquals("A very long le", nav.title)
    }

    @Test fun `views group under their shell container, schemaless ones draw the viewData items`() {
        assertEquals(listOf("acme.demo.list"), shell.views.getValue("acme.demo.main").map { it.id })
        assertFalse(shell.views.getValue("acme.demo.main").single().legacy)
        val old = shell.views.getValue("acme.old.old.box")
        assertEquals(listOf("old.list", "old.other"), old.map { it.id })
        assertTrue(old.all { it.legacy })
        assertEquals(LegacyView.ITEMS, old.first().body.root.path("bind"))
    }

    @Test fun `document types carry their pattern, title source and state provider`() {
        val d = shell.documents.single()
        assertEquals("acme.demo/box", d.type.id)
        assertEquals(UriPattern("ext", "acme.demo", "box"), d.type.pattern)
        assertTrue(d.type.multiple)
        assertFalse(d.type.supportsSplit)
        assertEquals("{name}", d.title.source)
        assertEquals("acme.demo.state", d.state?.command)
        assertEquals(5, d.state?.intervalSec)
        assertEquals("n1", d.type.title(dev.easyide.app.ui.shell.DocumentUri.parse("ext://acme.demo/box/n1")!!))
    }

    @Test fun `openers keep their priority and default to option`() {
        assertEquals(listOf(OpenerPriority.DEFAULT, OpenerPriority.OPTION), shell.openers.map { it.opener.priority })
        assertEquals(listOf("acme.demo/box", "acme.demo/box"), shell.openers.map { it.opener.typeId })
    }

    @Test fun `presets map placements, size classes and the stage split`() {
        val p = shell.presets.single().preset
        assertEquals("acme.demo.wide", p.id)
        assertEquals(mapOf(Placement.SIDEBAR to "acme.demo.main", Placement.PANEL to "terminal"), p.containers)
        assertEquals(setOf(Placement.SIDEBAR), p.open)
        assertEquals(2, p.groups)
        assertEquals(SplitAxis.COLUMN, p.axis)
        assertEquals(setOf(PaneArrangement.FULL, PaneArrangement.SINGLE_PANE), p.arrangements)
    }

    @Test fun `data sources name their view, kind and interval`() {
        val objectSource = shell.dataSources.first { it.viewId == "acme.demo.list" }
        assertEquals(3, objectSource.intervalSec)
        assertEquals(dev.easyide.extensions.contrib.ViewDataKind.OBJECT, objectSource.kind)
        assertEquals(dev.easyide.extensions.contrib.ViewDataKind.LIST, shell.dataSources.first { it.viewId == "old.list" }.kind)
    }

    @Test fun `a pack the user switched off contributes nothing to the shell`() {
        val off = ShellContributions.of(ExtFixtures.snapshot(docker, legacy), setOf("acme.demo"))
        assertTrue(off.containers.none { it.extensionId == "acme.demo" })
        assertTrue(off.documents.isEmpty() && off.presets.isEmpty() && off.openers.isEmpty() && off.badges.isEmpty())
        assertTrue(off.navigation.all { it.extensionId == "acme.old" })
        assertTrue(off.views.keys.none { it.startsWith("acme.demo") })
    }

    @Test fun `a view placed in a core container is left out`() {
        val pack = ExtFixtures.descriptor(ExtFixtures.manifest("""
            "contributes": { "views": { "explorer": [{ "id": "demo.extra", "name": "Extra" }] } }"""))
        assertTrue(ShellContributions.of(ExtFixtures.snapshot(pack)).views.isEmpty())
        assertNull(ShellContributions.of(ExtFixtures.snapshot(pack)).view("demo.extra"))
    }

    @Test fun `ids are made extension scoped and left alone when they already are`() {
        assertEquals("acme.demo.x", ShellContributions.shellId("acme.demo", "x"))
        assertEquals("acme.demo.x", ShellContributions.shellId("acme.demo", "acme.demo.x"))
    }
}
