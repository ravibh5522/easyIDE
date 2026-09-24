package dev.easyide.extensions.contrib

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributionOverridesTest {
    private data class Item(val id: String, val priority: Int)
    private fun ref(i: Item) = ContributionRef(ContributionRef.Kind.STATUS_BAR, null, i.id)
    private val byPriority = compareBy<Item> { -it.priority }
    private val items = listOf(Item("a", 1), Item("b", 3), Item("c", 2), Item("d", 0))

    @Test fun `default order applies when nothing is listed`() {
        val out = ContributionOverrides.NONE.apply("statusBar.left", items, ::ref, byPriority)
        assertEquals(listOf("b", "c", "a", "d"), out.map { it.id })
    }

    @Test fun `listed ids move to the front in listed order, the rest keep default order`() {
        val o = ContributionOverrides.of(emptyList(), mapOf("statusBar.left" to listOf("d", "a")))
        assertEquals(listOf("d", "a", "b", "c"), o.apply("statusBar.left", items, ::ref, byPriority).map { it.id })
        // Another location's order does not apply.
        assertEquals(listOf("b", "c", "a", "d"), o.apply("statusBar.right", items, ::ref, byPriority).map { it.id })
    }

    @Test fun `unknown ids are ignored and duplicates keep their first place`() {
        val o = ContributionOverrides.of(emptyList(), mapOf("x" to listOf("ghost", "c", "a", "c")))
        assertEquals(listOf("c", "a", "b", "d"), o.apply("x", items, ::ref, byPriority).map { it.id })
    }

    @Test fun `hidden refs are dropped before ordering`() {
        val o = ContributionOverrides.of(listOf("statusBar:b"), mapOf("x" to listOf("b", "d")))
        assertEquals(listOf("d", "c", "a"), o.apply("x", items, ::ref, byPriority).map { it.id })
    }

    @Test fun `non hideable refs stay visible`() {
        val o = ContributionOverrides.of(listOf("statusBar:builtin.safeMode", "statusBar:a"), emptyMap())
        assertTrue(o.isHidden(ContributionRef(ContributionRef.Kind.STATUS_BAR, null, "a")))
        assertFalse(o.isHidden(ContributionRef(ContributionRef.Kind.STATUS_BAR, null, "builtin.safeMode")))
        assertFalse(ContributionOverrides.isHideable("command:workbench.action.showCommands"))
    }

    @Test fun `menu refs match by command id within the menu location`() {
        val menu = listOf("x.one", "x.two", "x.three")
        fun r(c: String) = ContributionRef(ContributionRef.Kind.MENU, "editor/title", c)
        val o = ContributionOverrides.of(listOf("menu:editor/title:x.two"), mapOf("editor/title" to listOf("x.three")))
        assertEquals(listOf("x.three", "x.one"), o.apply("editor/title", menu, ::r))
    }

    @Test fun `order parsing skips malformed entries`() {
        val json = Json.parseToJsonElement("""{"a": ["x", 1, "y"], "b": "nope", "c": []}""")
        assertEquals(mapOf("a" to listOf("x", "y"), "c" to emptyList<String>()), ContributionOverrides.parseOrder(json))
        assertEquals(emptyMap<String, List<String>>(), ContributionOverrides.parseOrder(JsonPrimitive(3)))
        assertFalse(ContributionOverrides.isOrderValue(json))
        assertTrue(ContributionOverrides.isOrderValue(ContributionOverrides.encodeOrder(mapOf("a" to listOf("x")))))
    }

    @Test fun `hide and unhide write the whole list, refusing non hideable refs`() {
        assertEquals(listOf("a", "b", "c"), ContributionOverrides.withHidden(listOf("a", "b"), "c", hide = true))
        assertEquals(listOf("a", "c"), ContributionOverrides.withHidden(listOf("a", "c", "c"), "c", hide = true))
        assertEquals(listOf("a"), ContributionOverrides.withHidden(listOf("a", "b"), "b", hide = false))
        assertNull(ContributionOverrides.withHidden(emptyList(), "view:builtin.settings", hide = true))
        assertEquals(emptyList<String>(), ContributionOverrides.withHidden(listOf("view:builtin.settings"), "view:builtin.settings", hide = false))
    }

    @Test fun `move within the effective order`() {
        val eff = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), ContributionOverrides.moved(eff, "b", -1))
        assertEquals(listOf("a", "c", "b"), ContributionOverrides.moved(eff, "b", 1))
        assertNull(ContributionOverrides.moved(eff, "a", -1))
        assertNull(ContributionOverrides.moved(eff, "c", 1))
        assertNull(ContributionOverrides.moved(eff, "z", 1))
    }
}
