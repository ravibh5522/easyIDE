package dev.easyide.app.ui.screens.workspace.ext

import dev.easyide.app.extensions.ExtFixtures
import dev.easyide.app.extensions.adapters.MenuModel
import dev.easyide.app.ui.kit.KitMenuItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributedMenuTest {

    private val pack = ExtFixtures.descriptor(ExtFixtures.manifest("""
        "contributes": {
          "commands": [
            { "command": "demo.a", "title": "Alpha", "enablement": "false" },
            { "command": "demo.b", "title": "Beta" },
            { "command": "demo.c", "title": "Gamma" }
          ],
          "menus": {
            "editor/title": [
              { "command": "demo.a", "group": "1_first" },
              { "command": "demo.b", "group": "2_second" },
              { "command": "demo.c", "group": "2_second" }
            ]
          }
        }
    """))
    private val entries = MenuModel.items("editor/title", ExtFixtures.snapshot(pack), ExtFixtures.context(), emptySet())

    @Test fun `sections become actions with one divider between them and none at the ends`() {
        val items = contributedItems(entries.sections()) { }
        assertEquals(listOf("Alpha", null, "Beta", "Gamma"), items.map { (it as? KitMenuItem.Action)?.label })
        assertTrue(items[1] === KitMenuItem.Divider)
    }

    @Test fun `a disabled entry stays in the menu but is inert`() {
        val alpha = contributedItems(entries.sections()) { }.first() as KitMenuItem.Action
        assertFalse(alpha.enabled)
    }

    @Test fun `choosing an item runs its entry`() {
        val run = ArrayList<String>()
        val items = contributedItems(entries.sections()) { run += it.command.command }
        (items.last() as KitMenuItem.Action).onClick()
        assertEquals(listOf("demo.c"), run)
    }
}
