package dev.easyide.app.extensions.adapters

import dev.easyide.app.extensions.ExtFixtures
import dev.easyide.app.ui.screens.workspace.TerminalKeyboard
import dev.easyide.extensions.action.WorkspaceState
import dev.easyide.extensions.contrib.ContributionOverrides
import dev.easyide.extensions.contrib.Contributions
import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.extensions.contrib.StatusBarAlignment
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `workbench.contributions.order` in each rendered location, and `keyRows.layouts` (customization.md sec 6, 10). */
class CustomizationAdaptersTest {

    private val pack = ExtFixtures.descriptor(ExtFixtures.manifest("""
        "contributes": {
          "commands": [
            { "command": "demo.a", "title": "Alpha" },
            { "command": "demo.b", "title": "Beta" },
            { "command": "demo.c", "title": "Gamma" }
          ],
          "menus": {
            "editor/title": [
              { "command": "demo.c" },
              { "command": "demo.b", "group": "z@1" },
              { "command": "demo.a", "group": "navigation@2", "when": "editorLangId == python" }
            ]
          }
        },
        "easyide": {
          "keyRows": [
            { "id": "demo.py", "title": "Py", "when": "editorLangId == python", "keys": [ { "label": ":", "insert": ":" } ] },
            { "id": "demo.any", "title": "Any", "keys": [ { "label": "#" } ] }
          ],
          "statusBarItems": [
            { "id": "demo.l1", "text": "L1", "priority": 5 },
            { "id": "demo.l2", "text": "L2", "priority": 9 },
            { "id": "demo.r1", "text": "R1", "alignment": "right" }
          ]
        }
    """))
    private val snapshot = ExtFixtures.snapshot(pack, builtIn = Contributions(keyRows = listOf(TerminalKeyboard.row("Terminal"))))
    private val py = ExtFixtures.context("editorLangId" to "\"python\"")
    private val ws = WorkspaceState("/workspace", "demo", "env", "Env")

    @Test fun `menu order moves listed commands to the front`() {
        val order = mapOf("editor/title" to listOf("demo.c", "demo.b"))
        assertEquals(listOf("demo.c", "demo.b", "demo.a"), MenuModel.items("editor/title", snapshot, py, emptySet(), order = order).map { it.command.command })
        // Ordering ignores when for the editor list; hidden entries are not in it.
        assertEquals(listOf("demo.a", "demo.b", "demo.c"), MenuModel.orderedIds("editor/title", snapshot, emptySet(), emptyMap()))
        assertEquals(listOf("demo.a", "demo.c"), MenuModel.orderedIds("editor/title", snapshot, setOf("menu:editor/title:demo.b"), emptyMap()))
    }

    @Test fun `status bar order applies per side after priority`() {
        fun texts(order: Map<String, List<String>>) = StatusItems.items(snapshot, py, emptySet(), { null }, null, ws, order).map { it.text }
        assertEquals(listOf("L2", "L1", "R1"), texts(emptyMap()))
        assertEquals(listOf("L1", "L2", "R1"), texts(mapOf(ContributionOverrides.STATUS_BAR_LEFT to listOf("demo.l1"))))
        assertEquals(listOf("demo.l2", "demo.l1"), StatusItems.orderedIds(snapshot, StatusBarAlignment.LEFT, emptySet(), emptyMap()))
    }

    @Test fun `move rewrites the order of one location`() {
        val eff = ContributionLocations.effectiveIds("editor/title", snapshot, emptyList(), emptySet(), emptyMap())
        val next = ContributionLocations.moved(mapOf("other" to listOf("x")), "editor/title", eff, "demo.c", -1)!!
        assertEquals(mapOf("other" to listOf("x"), "editor/title" to listOf("demo.a", "demo.c", "demo.b")), next)
        assertEquals(listOf("demo.a", "demo.c", "demo.b"), MenuModel.orderedIds("editor/title", snapshot, emptySet(), next))
        assertNull(ContributionLocations.moved(emptyMap(), "editor/title", eff, "demo.a", -1))
        val owned = snapshot.statusBarItems.first { it.value.id == "demo.r1" }
        assertEquals(ContributionOverrides.STATUS_BAR_RIGHT, ContributionLocations.of(owned))
        assertEquals(ContributionOverrides.KEY_ROWS, ContributionLocations.of(snapshot.keyRows.first()))
        assertNull(ContributionLocations.of(snapshot.commands.first()))
    }

    @Test fun `user layouts come first and replace rows by id`() {
        val user = UserKeyRows.decode(Json.parseToJsonElement("""[
            { "id": "demo.py", "title": "My Py", "when": "editorLangId == python", "keys": [ { "label": "self", "insert": "self." } ] },
            { "id": "builtin.terminal", "title": "My shell", "keys": [ { "label": "Esc", "key": "escape" } ] }
        ]""")).rows
        val editor = KeyRows.active(KeySurface.EDITOR, snapshot, "auto", "auto", py, emptySet(), user)!!
        assertEquals("My Py", editor.title)
        assertNull(editor.owner)
        assertEquals(KeyAction.Insert("self."), editor.keys.single().action)
        val term = KeyRows.active(KeySurface.TERMINAL, snapshot, "auto", "auto", ExtFixtures.context(), emptySet(), user)!!
        assertEquals("My shell", term.title)
        assertEquals(listOf("demo.py", "builtin.terminal", "demo.any"), KeyRows.available(snapshot, user, emptySet(), emptyMap()).map { it.id })
    }

    @Test fun `keyRows order changes the auto pick and hidden rows are flagged`() {
        assertEquals("demo.py", KeyRows.active(KeySurface.EDITOR, snapshot, "auto", "auto", py, emptySet())!!.id)
        val order = mapOf(ContributionOverrides.KEY_ROWS to listOf("demo.any"))
        assertEquals("demo.any", KeyRows.active(KeySurface.EDITOR, snapshot, "auto", "auto", py, emptySet(), order = order)!!.id)
        val choices = KeyRows.available(snapshot, emptyList(), setOf("keyRow:demo.any"), emptyMap())
        assertEquals(listOf("demo.py", "demo.any", "builtin.terminal"), choices.map { it.id })
        assertTrue(choices.single { it.id == "demo.any" }.hidden)
        assertEquals(listOf("demo.py", "builtin.terminal"), ContributionLocations.effectiveIds(ContributionOverrides.KEY_ROWS, snapshot, emptyList(), setOf("keyRow:demo.any"), emptyMap()))
    }

    @Test fun `user layout problems drop only the bad row or key`() {
        val r = UserKeyRows.decode(Json.parseToJsonElement("""[
            { "id": "ok", "keys": [ { "label": "a" }, { "label": "b", "insert": "b", "command": "x" }, { "label": "c", "snippet": "c$1", "longPress": { "command": "x.y" } } ] },
            { "id": "ok", "keys": [ { "label": "dup" } ] },
            { "title": "no id", "keys": [ { "label": "a" } ] },
            { "id": "badwhen", "when": "a ==", "keys": [ { "label": "a" } ] },
            { "id": "nokeys", "keys": [] },
            7
        ]"""))
        assertEquals(listOf("ok"), r.rows.map { it.id })
        assertEquals("ok", r.rows.single().title)
        assertEquals(listOf(KeyAction.Insert("a"), KeyAction.Snippet("c$1")), r.rows.single().keys.map { it.action })
        assertEquals(KeyAction.Command("x.y"), r.rows.single().keys.last().longPress)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), r.problems.map { it.index })
        assertEquals(emptyList<Any>(), UserKeyRows.decode(null).rows)
    }
}
