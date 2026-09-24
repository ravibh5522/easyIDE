package dev.easyide.extensions.view

import dev.easyide.extensions.Fixtures
import dev.easyide.extensions.Manifests
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.PackageLayout
import dev.easyide.extensions.manifest.PackageLayoutReader
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.ParseResult
import dev.easyide.extensions.whenclause.ContextLookup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewPlanTest {
    private val docker: ExtensionDescriptor by lazy {
        val files = (PackageLayoutReader.read(Fixtures.dir("ui-docker"), PackageLimits.DEFAULT) as PackageLayout.Ok).files
        (Manifests.parser.parse(files) as ParseResult.Ok).descriptor
    }

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    /** A view document from inline schema JSON, through the real parser. */
    private fun doc(root: String, state: String = "{}"): ViewDocument {
        val view = """{ "viewSchema": 1, "state": $state, "root": $root }"""
        val manifest = Manifests.minimal("""
            "contributes": { "commands": [{ "command": "demo.go", "title": "Go" }],
              "viewsContainers": { "sidebar": [{ "id": "acme.demo.box", "title": "Box", "icon": "box" }] },
              "views": { "acme.demo.box": [{ "id": "acme.demo.v", "name": "V", "schema": "./v.json" }] } },
            "easyide": { "capabilities": ["ui.contribute"], "actions": { "demo.go": { "type": "showMessage", "text": "go" } },
              "documents": [{ "type": "acme.demo/note", "title": "N", "icon": "box", "schema": "./v.json" }] }""")
        return Manifests.ok(manifest, mapOf("v.json" to view)).contributes.views.single().schema!!
    }

    private fun ready(root: String, data: String = "{}", env: PlanEnv = PlanEnv()): PlanNode =
        (ViewPlan.build(doc(root), obj(data), env) as PlanResult.Ready).root

    private fun rows(n: PlanNode) = (n.payload as Payload.Rows).rows

    @Test fun `text resolves against the data and skips nodes whose when is false`() {
        val root = ready("""{ "type": "column", "children": [
            { "type": "text", "value": "Hello {name}" },
            { "type": "text", "value": "hidden", "when": "flag" },
            { "type": "text", "value": "shown", "when": "!flag" }] }""", """{ "name": "Ana", "flag": false }""")
        assertEquals(listOf("Hello Ana", "shown"), root.children.map { it.text["value"] })
    }

    @Test fun `when reads the item first and then the shell context keys`() {
        val env = PlanEnv(context = ContextLookup { if (it == "windowSizeClass") JsonPrimitive("compact") else null })
        val root = ready("""{ "type": "column", "children": [
            { "type": "text", "value": "phone", "when": "windowSizeClass == compact" },
            { "type": "text", "value": "tablet", "when": "windowSizeClass == expanded" }] }""", env = env)
        assertEquals(listOf("phone"), root.children.map { it.text["value"] })
    }

    @Test fun `a docker list plans a row per container, filtered by the query`() {
        val data = obj("""{ "query": "web", "containers": [
            { "id": "a1", "name": "web", "image": "nginx", "state": "running", "status": "Up 2h" },
            { "id": "b2", "name": "db", "image": "postgres", "state": "exited", "status": "Exited" },
            { "id": "c3", "name": "cache", "image": "web-cache", "state": "exited", "status": "Exited" }] }""")
        val root = (ViewPlan.build(docker.contributes.views.single().schema!!, data) as PlanResult.Ready).root
        val list = root.children[1]
        val r = rows(list)
        assertEquals(listOf("a1", "c3"), (0 until r.count).map { r.key(it) })
        val web = r.at(0)!!
        assertEquals("row", web.type.wire)
        // running: the success dot and the stop button show, not the start button.
        val labels = web.children.mapNotNull { it.text["label"] }
        assertEquals(listOf("running", "Stop web"), labels)
        assertTrue(list.fills)
    }

    @Test fun `an empty list shows its empty node, a filtered-out list too`() {
        val root = ready("""{ "type": "list", "bind": "rows", "item": { "type": "text", "value": "{n}" },
            "empty": { "type": "emptyState", "message": "Nothing" } }""", """{ "rows": [] }""")
        assertEquals("Nothing", root.empty?.text?.get("message"))
        assertEquals(0, rows(root).count)
    }

    @Test fun `row keys use the key path and fall back to the index`() {
        val root = ready("""{ "type": "list", "bind": "rows", "key": "id", "item": { "type": "text", "value": "{id}" } }""", """{ "rows": [{ "id": "x" }, { "n": 1 }] }""")
        assertEquals(listOf("x", "#1"), (0 until 2).map { rows(root).key(it) })
    }

    @Test fun `duplicate row keys are made unique so a keyed list never collides`() {
        val root = ready("""{ "type": "list", "bind": "rows", "key": "id", "item": { "type": "text", "value": "{id}" } }""", """{ "rows": [{ "id": "x" }, { "id": "x" }, { "id": "x" }] }""")
        assertEquals(listOf("x", "x~2", "x~3"), (0 until 3).map { rows(root).key(it) })
    }

    @Test fun `an event resolves its args at fire time and local values shadow the data`() {
        val root = ready("""{ "type": "column", "children": [
            { "type": "composer", "bind": "draft", "action": "demo.go", "args": { "text": "{draft}", "n": "{count}" },
              "confirm": { "title": "Send {draft}?" },
              "before": [{ "op": "append", "path": "messages", "value": { "role": "user", "text": "{draft}" } }] }] }""", """{ "draft": "old", "count": 3 }""")
        val resolved = root.children.single().action!!.resolve(mapOf("draft" to JsonPrimitive("typed")))
        assertEquals(ResolvedTarget.Command("demo.go", obj("""{ "text": "typed", "n": 3 }""")), resolved.target)
        assertEquals("Send typed?", resolved.confirm?.title)
        assertEquals(obj("""{ "role": "user", "text": "typed" }"""), resolved.before.single().value)
    }

    @Test fun `an item action resolves against its row`() {
        val root = ready("""{ "type": "list", "bind": "rows", "item": { "type": "row", "open": "ext://acme.demo/note/{id}", "children": [] } }""", """{ "rows": [{ "id": "n7" }] }""")
        val open = rows(root).at(0)!!.action!!.resolve().target as ResolvedTarget.Open
        assertEquals("ext://acme.demo/note/n7", open.uri)
    }

    @Test fun `enabledWhen disables a button without hiding it`() {
        val root = ready("""{ "type": "button", "label": "Go", "action": "demo.go", "enabledWhen": "ready" }""", """{ "ready": false }""")
        assertEquals(false, root.enabled)
        assertEquals(true, ready("""{ "type": "button", "label": "Go", "action": "demo.go", "enabledWhen": "ready" }""", """{ "ready": true }""").enabled)
    }

    @Test fun `controls carry their bound value and options`() {
        val field = ready("""{ "type": "field", "bind": "form.host", "label": "Host" }""", """{ "form": { "host": "localhost" } }""")
        assertEquals("form.host", field.bind)
        assertEquals(JsonPrimitive("localhost"), field.value)
        val select = ready("""{ "type": "select", "bind": "env", "optionsFrom": "envs" }""", """{ "env": "b", "envs": [{ "label": "Bee", "value": "b" }, "c"] }""")
        assertEquals(listOf("Bee", "c"), select.options.map { it.label })
        val fixed = ready("""{ "type": "select", "bind": "env", "options": ["one", { "label": "Two", "value": 2 }] }""")
        assertEquals(listOf("one", "Two"), fixed.options.map { it.label })
    }

    @Test fun `tabs plan only the selected tab and list every title`() {
        val schema = """{ "type": "tabs", "children": [
            { "type": "tab", "title": "Logs", "children": [{ "type": "text", "value": "one" }] },
            { "type": "tab", "title": "Env", "children": [{ "type": "text", "value": "two" }] }] }"""
        val first = ready(schema)
        assertEquals(listOf("Logs", "Env"), first.text["titles"]!!.split('\u0001'))
        assertEquals("one", first.children.single().children.single().text["value"])
        val second = ready(schema, env = PlanEnv(ui = ViewUiState(tabs = mapOf(first.key to 1))))
        assertEquals("two", second.children.single().children.single().text["value"])
    }

    @Test fun `tree rows follow the expanded set`() {
        val schema = """{ "type": "tree", "bind": "files", "key": "id", "item": { "type": "text", "value": "{name}" } }"""
        val data = """{ "files": [{ "id": "d", "name": "dir", "children": [{ "id": "f", "name": "file" }] }, { "id": "g", "name": "gone" }] }"""
        val closed = ready(schema, data).payload as Payload.TreeRows
        assertEquals(listOf(true, false), (0 until closed.rows.count).map { closed.rows.at(it)!!.expandable })
        assertEquals(2, closed.rows.count)
        val open = ready(schema, data, PlanEnv(ui = ViewUiState(expanded = setOf("/d")))).payload as Payload.TreeRows
        assertEquals(listOf(0, 1, 0), (0 until open.rows.count).map { open.rows.at(it)!!.depth })
    }

    @Test fun `table cells format and rows are lazy`() {
        val root = ready("""{ "type": "table", "bind": "rows", "columns": [{ "title": "Name", "field": "name" }, { "title": "Size", "field": "size", "format": "bytes" }] }""",
            """{ "rows": [{ "name": "a", "size": 2048 }] }""")
        val t = root.payload as Payload.TableRows
        assertEquals(listOf("Name", "Size"), t.columns.map { it.title })
        assertEquals(listOf("a", "2.0 KB"), t.rows.at(0))
    }

    @Test fun `chat messages and log lines are read from data and capped by their rings`() {
        val chat = ready("""{ "type": "chat", "bind": "messages" }""", """{ "messages": [
            { "id": "1", "role": "user", "text": "hi" },
            { "id": "2", "role": "assistant", "text": "yo", "state": "streaming", "tool": { "name": "sh", "command": "ls", "status": "done", "output": "a" } }, 5] }""")
        val messages = (chat.payload as Payload.Messages).messages
        assertEquals(listOf("user", "assistant"), messages.map { it.role })
        assertEquals("streaming", messages[1].state)
        assertEquals("ls", messages[1].tool?.command)
        val many = (1..ViewLimits.CHAT_MESSAGES + 5).joinToString(",") { """{ "id": "$it", "text": "t" }""" }
        val capped = ready("""{ "type": "chat", "bind": "m" }""", """{ "m": [$many] }""").payload as Payload.Messages
        assertEquals(ViewLimits.CHAT_MESSAGES, capped.messages.size)
        assertEquals("${ViewLimits.CHAT_MESSAGES + 5}", capped.messages.last().id)
        val lines = (1..ViewLimits.LOG_LINES + 3).joinToString(",") { "\"l$it\"" }
        val log = ready("""{ "type": "logStream", "bind": "l" }""", """{ "l": [$lines] }""").payload as Payload.Lines
        assertEquals(ViewLimits.LOG_LINES, log.lines.size)
        assertEquals("l${ViewLimits.LOG_LINES + 3}", log.lines.last())
        assertEquals(listOf("a", "b"), (ready("""{ "type": "logStream", "bind": "l" }""", """{ "l": "a\nb" }""").payload as Payload.Lines).lines)
    }

    @Test fun `sparkline keeps numbers only`() {
        val s = ready("""{ "type": "sparkline", "bind": "cpu", "label": "CPU" }""", """{ "cpu": [1, "x", 2.5, null, 4] }""").payload as Payload.Series
        assertEquals(listOf(1.0, 2.5, 4.0), s.values)
    }

    @Test fun `a stable key does not change when data changes`() {
        val schema = """{ "type": "column", "children": [{ "type": "text", "id": "title", "value": "{t}" }, { "type": "text", "value": "{t}" }] }"""
        val a = ready(schema, """{ "t": "one" }""")
        val b = ready(schema, """{ "t": "two" }""")
        assertEquals(listOf("root/title", "root/1"), a.children.map { it.key })
        assertEquals(a.children.map { it.key }, b.children.map { it.key })
    }

    @Test fun `a reserved component is skipped and logged, never drawn`() {
        val terminal = ViewNode(ViewType.TERMINAL, null, null, emptyMap(), pointer = "/root/children/1")
        val text = ViewNode(ViewType.TEXT, null, null, mapOf("value" to PropValue.Text(ViewTemplate.literal("kept"))), pointer = "/root/children/0")
        val root = ViewNode(ViewType.COLUMN, null, null, emptyMap(), listOf(text, terminal), pointer = "/root")
        val log = ArrayList<String>()
        val plan = ViewPlan.build(ViewDocument("v.json", JsonObject(emptyMap()), root, 3, 2), obj("{}"), PlanEnv(log = { log += it })) as PlanResult.Ready
        assertEquals(listOf("kept"), plan.root.children.map { it.text["value"] })
        assertEquals(1, log.size)
        assertTrue(log.single().contains("terminal"))
    }

    @Test fun `the render budget makes a view unavailable instead of drawing too much`() {
        // 60 rows of a 40-node template inside a row (not a scrolling body) exceed 2000 drawn nodes.
        val template = """{ "type": "column", "children": [${(1..39).joinToString(",") { """{ "type": "text", "value": "x" }""" }}] }"""
        val schema = """{ "type": "row", "children": [{ "type": "list", "bind": "r", "item": $template }] }"""
        val many = (1..100).joinToString(",") { """{ "id": $it }""" }
        val result = ViewPlan.build(doc(schema), obj("""{ "r": [$many] }"""))
        assertTrue(result.toString(), result is PlanResult.Unavailable)
        // The same list as the scrolling body only counts the rows a screen shows (40 x 40 = 1600).
        val body = ViewPlan.build(doc("""{ "type": "list", "bind": "r", "item": $template }"""), obj("""{ "r": [$many] }"""))
        assertTrue(body.toString(), body is PlanResult.Ready)
    }

    @Test fun `inline lists are capped and lazy lists are not`() {
        val many = (1..500).joinToString(",") { """{ "id": $it }""" }
        val inline = ready("""{ "type": "row", "children": [{ "type": "list", "bind": "r", "item": { "type": "text", "value": "{id}" } }] }""", """{ "r": [$many] }""")
        assertEquals(ViewLimits.MAX_INLINE_ROWS, rows(inline.children.single()).count)
        assertEquals(500, rows(ready("""{ "type": "list", "bind": "r", "item": { "type": "text", "value": "{id}" } }""", """{ "r": [$many] }""")).count)
    }

    @Test fun `bound rows are capped at the row limit`() {
        val over = kotlinx.serialization.json.JsonArray((1..ViewLimits.MAX_ROWS + 10).map { JsonObject(mapOf("id" to JsonPrimitive(it))) })
        val plan = ViewPlan.build(doc("""{ "type": "list", "bind": "r", "item": { "type": "text", "value": "{id}" } }"""), JsonObject(mapOf("r" to over))) as PlanResult.Ready
        assertEquals(ViewLimits.MAX_ROWS, (plan.root.payload as Payload.Rows).rows.count)
    }

    @Test fun `icons and flags come through`() {
        val root = ready("""{ "type": "column", "children": [
            { "type": "iconButton", "icon": "play", "label": "Go", "action": "demo.go" },
            { "type": "field", "bind": "a", "mono": true, "multiline": false }] }""")
        assertEquals("play", (root.children[0].icons["icon"] as dev.easyide.extensions.contrib.CommandIcon.Token).name)
        assertEquals(mapOf("mono" to true, "multiline" to false), root.children[1].flags)
    }
}
