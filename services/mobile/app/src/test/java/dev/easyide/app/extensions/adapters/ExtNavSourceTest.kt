package dev.easyide.app.extensions.adapters

import dev.easyide.app.extensions.ExtFixtures
import dev.easyide.app.ui.shell.ext.ExtBadge
import dev.easyide.app.ui.shell.nav.NavBadgeValue
import dev.easyide.extensions.contrib.BadgeKind
import dev.easyide.extensions.whenclause.ContextLookup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExtNavSourceTest {
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="currentColor" d="M4 4h16v16H4z"/></svg>"""
    private val pack = ExtFixtures.descriptor(ExtFixtures.manifest("""
        "contributes": { "commands": [{ "command": "acme.demo.open", "title": "Open" }],
          "viewsContainers": { "sidebar": [{ "id": "acme.demo.main", "title": "Box", "icon": "./i.svg", "scope": "both" }] } },
        "easyide": { "capabilities": ["ui.contribute"],
          "actions": { "acme.demo.open": { "type": "showMessage", "text": "hi" } },
          "navigation": [
            { "id": "acme.demo.always", "title": "Box", "icon": "box", "target": { "container": "acme.demo.main" } },
            { "id": "acme.demo.conditional", "title": "Docker", "icon": "box", "target": { "command": "acme.demo.open" }, "when": "workspaceHas == yes" }] }
    """), mapOf("i.svg" to svg))

    private fun ctx(vararg pairs: Pair<String, String>): ContextLookup {
        val map: Map<String, JsonElement> = pairs.associate { (k, v) -> k to JsonPrimitive(v) }
        return ContextLookup { map[it] }
    }

    @Test fun `an item whose clause fails is left out and its clause is spent once it shows`() = runTest(UnconfinedTestDispatcher()) {
        val context = MutableStateFlow(ctx())
        val source = ExtNavSource(MutableStateFlow(ShellContributions.of(ExtFixtures.snapshot(pack))), context, MutableStateFlow(emptyMap()), backgroundScope)
        assertEquals(listOf("acme.demo.always"), source.contributions.value.map { it.item.id })
        context.value = ctx("workspaceHas" to "yes")
        assertEquals(listOf("acme.demo.always", "acme.demo.conditional"), source.contributions.value.map { it.item.id })
        assertTrue(source.contributions.value.all { it.item.condition == null })
        assertEquals(setOf("acme.demo.open"), source.commands)
    }

    @Test fun `holds evaluates a clause against the live context and never throws on a bad one`() = runTest(UnconfinedTestDispatcher()) {
        val context = MutableStateFlow(ctx("a" to "1"))
        val source = ExtNavSource(MutableStateFlow(ShellContributions.of(ExtFixtures.snapshot(pack))), context, MutableStateFlow(emptyMap()), backgroundScope)
        assertTrue(source.holds("a == 1"))
        assertFalse(source.holds("a == 2"))
        assertFalse(source.holds("&& &&"))
    }

    @Test fun `badges read numbers and truthiness from the view data`() {
        val badges = listOf(ExtBadge("n.count", "v1", "running", BadgeKind.COUNT), ExtBadge("n.dot", "v2", "unread", BadgeKind.DOT), ExtBadge("n.none", "v3", "x", BadgeKind.COUNT))
        fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject
        val data = mapOf("v1" to obj("""{ "running": 3 }"""), "v2" to obj("""{ "unread": true }"""), "v3" to obj("""{ "x": 0 }"""))
        assertEquals(mapOf("n.count" to NavBadgeValue.Count(3), "n.dot" to NavBadgeValue.Dot), Badges.values(badges, data))
        assertEquals(emptyMap<String, NavBadgeValue>(), Badges.values(badges, mapOf("v2" to obj("""{ "unread": false }"""), "v1" to obj("""{ "running": "many" }"""))))
        assertEquals(emptyMap<String, NavBadgeValue>(), Badges.values(badges, emptyMap()))
    }

    @Test fun `badge values follow the data as it changes`() = runTest(UnconfinedTestDispatcher()) {
        val data = MutableStateFlow<Map<String, JsonObject>>(emptyMap())
        val shell = MutableStateFlow(ShellContributions.of(ExtFixtures.snapshot(pack)).copy(badges = listOf(ExtBadge("acme.demo.always", "v", "n", BadgeKind.COUNT))))
        val source = ExtNavSource(shell, MutableStateFlow(ctx()), data, backgroundScope)
        assertTrue(source.badges.value.isEmpty())
        data.value = mapOf("v" to (Json.parseToJsonElement("""{ "n": 2 }""") as JsonObject))
        assertEquals(mapOf("acme.demo.always" to NavBadgeValue.Count(2)), source.badges.value)
    }
}
