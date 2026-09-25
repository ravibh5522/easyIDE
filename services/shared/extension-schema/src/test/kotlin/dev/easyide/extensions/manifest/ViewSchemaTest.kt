package dev.easyide.extensions.manifest

import dev.easyide.extensions.Manifests
import dev.easyide.extensions.view.ActionTarget
import dev.easyide.extensions.view.EffectOp
import dev.easyide.extensions.view.IntoMode
import dev.easyide.extensions.view.ResultParse
import dev.easyide.extensions.view.ViewLimits
import dev.easyide.extensions.view.ViewType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `viewSchema: 1` file rules: catalog, props, events, ids and every hard limit, each with a negative case. */
class ViewSchemaTest {
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="currentColor" d="M4 4h16v16H4z"/></svg>"""

    private fun manifest(view: String, extraCommands: String = "") = Manifests.minimal("""
        "contributes": {
          "commands": [{ "command": "demo.go", "title": "Go" }$extraCommands],
          "viewsContainers": { "sidebar": [{ "id": "acme.demo.box", "title": "Box", "icon": "./i.svg" }] },
          "views": { "acme.demo.box": [{ "id": "acme.demo.v", "name": "V", "schema": "./v.json" }] } },
        "easyide": { "capabilities": ["ui.contribute"], "actions": { "demo.go": { "type": "showMessage", "text": "go" } } }""")

    private fun root(node: String, state: String = "") = """{ "viewSchema": 1, ${if (state.isEmpty()) "" else "\"state\": $state, "}"root": $node }"""

    private fun parse(view: String) = Manifests.parse(manifest(view), mapOf("i.svg" to svg, "v.json" to view))

    private fun viewErrors(view: String): List<Diagnostic> = when (val r = parse(view)) {
        is ParseResult.Invalid -> r.errors.filter { it.file == "v.json" }
        is ParseResult.Ok -> emptyList()
    }

    private fun codes(view: String) = viewErrors(view).map { it.code }

    private fun ok(view: String): ViewDocumentHolder = when (val r = parse(view)) {
        is ParseResult.Ok -> ViewDocumentHolder(r.descriptor.contributes.views.single().schema!!, r.warnings)
        is ParseResult.Invalid -> error("expected a valid view, got ${r.errors}")
    }

    private class ViewDocumentHolder(val doc: dev.easyide.extensions.view.ViewDocument, val warnings: List<Diagnostic>)

    @Test fun `a minimal view decodes and reports its size`() {
        val h = ok(root("""{ "type": "column", "children": [{ "type": "text", "value": "Hi {name}" }] }"""))
        assertEquals(2, h.doc.nodes)
        assertEquals(2, h.doc.depth)
        assertEquals("v.json", h.doc.file)
        assertEquals(ViewType.TEXT, h.doc.root.children.single().type)
    }

    @Test fun `the version must be exactly 1`() {
        assertEquals(listOf(DiagnosticCode.VIEW_VERSION), codes("""{ "viewSchema": 2, "root": { "type": "text", "value": "x" } }"""))
        assertEquals(listOf(DiagnosticCode.VIEW_VERSION), codes("""{ "root": { "type": "text", "value": "x" } }"""))
    }

    @Test fun `syntax and shape errors are reported against the view file`() {
        assertEquals(listOf(DiagnosticCode.VIEW_SYNTAX), codes("{ \"viewSchema\": 1, "))
        assertEquals(listOf(DiagnosticCode.VIEW_SYNTAX), codes("[]"))
        assertEquals(listOf(DiagnosticCode.VIEW_PROP), codes("""{ "viewSchema": 1, "root": { "type": "text", "value": "x" }, "extra": 1 }"""))
        assertEquals(listOf(DiagnosticCode.VIEW_PROP), codes("""{ "viewSchema": 1 }"""))
    }

    @Test fun `unknown and reserved components are errors`() {
        assertEquals(listOf(DiagnosticCode.VIEW_COMPONENT), codes(root("""{ "type": "hologram" }""")))
        val terminal = viewErrors(root("""{ "type": "terminal" }""")).single()
        assertEquals(DiagnosticCode.VIEW_COMPONENT, terminal.code)
        assertTrue(terminal.message, terminal.message.contains("reserved"))
        assertEquals("/root/type", terminal.pointer)
    }

    @Test fun `unknown and missing props are errors with the pointer of the prop`() {
        val unknown = viewErrors(root("""{ "type": "text", "value": "x", "colour": "red" }""")).single()
        assertEquals("/root/colour", unknown.pointer)
        val missing = viewErrors(root("""{ "type": "text" }""")).single()
        assertEquals("/root/value", missing.pointer)
        assertEquals(listOf(DiagnosticCode.VIEW_PROP), codes(root("""{ "type": "text", "value": "x", "role": "shout" }""")))
        assertEquals(listOf(DiagnosticCode.VIEW_PROP), codes(root("""{ "type": "toggle", "label": "L", "bind": "a b" }""")))
        assertEquals(listOf(DiagnosticCode.VIEW_PROP), codes(root("""{ "type": "text", "value": "x", "weight": 0 }""")))
    }

    @Test fun `event props only exist on interactive components`() {
        assertEquals(listOf(DiagnosticCode.VIEW_PROP), codes(root("""{ "type": "text", "value": "x", "action": "demo.go" }""")))
        ok(root("""{ "type": "row", "action": "demo.go", "children": [] }"""))
    }

    @Test fun `templates are parsed and a bad one is refused`() {
        assertEquals(listOf(DiagnosticCode.VIEW_TEMPLATE), codes(root("""{ "type": "text", "value": "{unclosed" }""")))
        assertEquals(listOf(DiagnosticCode.VIEW_TEMPLATE), codes(root("""{ "type": "text", "value": "{a|shout}" }""")))
        ok(root("""{ "type": "text", "value": "{{literal}} {a.b|bytes}" }"""))
    }

    @Test fun `slots follow the catalog`() {
        assertEquals(listOf(DiagnosticCode.VIEW_PROP), codes(root("""{ "type": "text", "value": "x", "children": [] }""")))
        assertEquals(listOf(DiagnosticCode.VIEW_PROP), codes(root("""{ "type": "split", "children": [{ "type": "text", "value": "x" }] }""")))
        assertEquals(listOf(DiagnosticCode.VIEW_PROP), codes(root("""{ "type": "list", "bind": "a" }""")))
        assertEquals(listOf(DiagnosticCode.VIEW_COMPONENT), codes(root("""{ "type": "tabs", "children": [{ "type": "text", "value": "x" }] }""")))
        assertEquals(listOf(DiagnosticCode.VIEW_COMPONENT), codes(root("""{ "type": "tab", "title": "T", "children": [] }""")))
    }

    @Test fun `select needs exactly one option source`() {
        assertEquals(listOf(DiagnosticCode.VIEW_PROP), codes(root("""{ "type": "select", "bind": "a" }""")))
        ok(root("""{ "type": "select", "bind": "a", "options": ["one", { "label": "Two", "value": 2 }] }"""))
        ok(root("""{ "type": "select", "bind": "a", "optionsFrom": "choices" }"""))
    }

    @Test fun `accessibility labels are required where there is no text`() {
        assertEquals(listOf(DiagnosticCode.VIEW_PROP), codes(root("""{ "type": "iconButton", "icon": "play", "action": "demo.go" }""")))
        assertEquals(listOf(DiagnosticCode.VIEW_PROP), codes(root("""{ "type": "statusDot" }""")))
    }

    @Test fun `ids are unique within a view`() {
        val dup = root("""{ "type": "column", "children": [{ "type": "text", "id": "a", "value": "x" }, { "type": "text", "id": "a", "value": "y" }] }""")
        assertEquals(listOf(DiagnosticCode.VIEW_ID), codes(dup))
    }

    @Test fun `a view holds one composer`() {
        val two = root("""{ "type": "column", "children": [
            { "type": "composer", "bind": "a", "action": "demo.go" }, { "type": "composer", "bind": "b", "action": "demo.go" }] }""")
        assertEquals(listOf(DiagnosticCode.VIEW_COMPOSER), codes(two))
        ok(root("""{ "type": "composer", "bind": "draft", "action": "demo.go" }"""))
    }

    @Test fun `a destructive button must carry confirm`() {
        val bare = root("""{ "type": "button", "label": "Delete", "style": "danger", "action": "demo.go" }""")
        assertEquals(listOf(DiagnosticCode.VIEW_CONFIRM), codes(bare))
        ok(root("""{ "type": "button", "label": "Delete", "style": "danger", "action": "demo.go", "confirm": { "title": "Delete?", "destructive": true } }"""))
        assertEquals(listOf(DiagnosticCode.VIEW_CONFIRM), codes(root("""{ "type": "iconButton", "icon": "x", "label": "Rm", "tone": "danger", "action": "demo.go" }""")))
    }

    @Test fun `buttons need somewhere to go`() {
        assertEquals(listOf(DiagnosticCode.VIEW_ACTION), codes(root("""{ "type": "button", "label": "Nothing" }""")))
        assertEquals(listOf(DiagnosticCode.VIEW_ACTION), codes(root("""{ "type": "button", "label": "Both", "action": "demo.go", "open": "ext://acme.demo/n/1" }""")))
    }

    @Test fun `results write back with a mode and a parse`() {
        val doc = ok(root("""{ "type": "button", "label": "Load", "action": "demo.go", "as": "rows", "mode": "append", "parse": "lines",
            "before": [{ "op": "append", "path": "messages", "value": { "role": "user", "text": "{draft}" } }, { "op": "clear", "path": "draft" }],
            "after": [{ "op": "set", "path": "busy", "value": false }] }""")).doc
        val action = doc.root.action!!
        assertEquals(ActionTarget.Command("demo.go"), action.target)
        assertEquals("rows", action.into?.path)
        assertEquals(IntoMode.APPEND, action.into?.mode)
        assertEquals(ResultParse.LINES, action.into?.parse)
        assertEquals(listOf(EffectOp.APPEND, EffectOp.CLEAR), action.before.map { it.op })
        assertEquals(listOf("busy"), action.after.map { it.path })
    }

    @Test fun `as dot merges an object result into the data`() {
        val doc = ok(root("""{ "type": "button", "label": "Load", "action": "demo.go", "as": "." }""")).doc
        assertEquals(dev.easyide.extensions.view.ViewData.MERGE, doc.root.action?.into?.path)
    }

    @Test fun `as needs a command and mode goes with as`() {
        assertEquals(listOf(DiagnosticCode.VIEW_PROP), codes(root("""{ "type": "button", "label": "L", "action": "demo.go", "mode": "append" }""")))
        assertTrue(DiagnosticCode.VIEW_ACTION in codes(root("""{ "type": "row", "open": "ext://acme.demo/n/1", "as": "x", "children": [] }""")))
        assertEquals(listOf(DiagnosticCode.VIEW_PROP), codes(root("""{ "type": "button", "label": "L", "action": "demo.go", "before": [{ "op": "set", "path": "a" }] }""")))
    }

    @Test fun `an inline action is checked by the manifest action schema`() {
        val bad = root("""{ "type": "button", "label": "Go", "action": { "type": "sandboxExec", "command": "ls", "output": "capture" } }""")
        val e = viewErrors(bad)
        assertEquals(DiagnosticCode.SCHEMA, e.first().code)
        assertEquals("/root/action/command", e.first().pointer)
        val unknown = root("""{ "type": "button", "label": "Go", "action": { "type": "launchRockets" } }""")
        assertEquals(DiagnosticCode.SCHEMA, viewErrors(unknown).first().code)
    }

    @Test fun `regexes and images in props are checked`() {
        assertEquals(listOf(DiagnosticCode.REGEX), codes(root("""{ "type": "field", "bind": "a", "validate": "(" }""")))
        assertEquals(listOf(DiagnosticCode.PATH_MISSING), codes(root("""{ "type": "image", "src": "./nope.png", "label": "N" }""")))
    }

    // ---- limits: one negative case per hard limit

    @Test fun `a view file over 128 KB is refused`() {
        val big = root("""{ "type": "text", "value": "x" }""", """{ "pad": "${"a".repeat(ViewLimits.MAX_FILE_BYTES)}" }""")
        assertEquals(listOf(DiagnosticCode.VIEW_LIMIT), codes(big))
    }

    @Test fun `nesting deeper than 12 is refused, 12 is fine`() {
        fun nest(depth: Int): String = if (depth == 1) """{ "type": "text", "value": "leaf" }""" else """{ "type": "column", "children": [${nest(depth - 1)}] }"""
        assertEquals(ViewLimits.MAX_DEPTH, ok(root(nest(ViewLimits.MAX_DEPTH))).doc.depth)
        assertEquals(listOf(DiagnosticCode.VIEW_LIMIT), codes(root(nest(ViewLimits.MAX_DEPTH + 1))))
    }

    @Test fun `more than 2000 components are refused, exactly 2000 are fine`() {
        fun flat(n: Int) = """{ "type": "column", "children": [${(1 until n).joinToString(",") { """{ "type": "text", "value": "x" }""" }}] }"""
        assertEquals(ViewLimits.MAX_NODES, ok(root(flat(ViewLimits.MAX_NODES))).doc.nodes)
        assertEquals(listOf(DiagnosticCode.VIEW_LIMIT), codes(root(flat(ViewLimits.MAX_NODES + 1))))
    }

    @Test fun `a string longer than 4096 characters is refused`() {
        assertEquals(listOf(DiagnosticCode.VIEW_LIMIT), codes(root("""{ "type": "text", "value": "${"a".repeat(ViewLimits.MAX_STRING + 1)}" }""")))
        ok(root("""{ "type": "text", "value": "${"a".repeat(ViewLimits.MAX_STRING)}" }"""))
    }

    @Test fun `literal lists are capped`() {
        val options = (1..ViewLimits.MAX_LITERAL_LIST + 1).joinToString(",") { "\"o$it\"" }
        assertEquals(listOf(DiagnosticCode.VIEW_LIMIT), codes(root("""{ "type": "select", "bind": "a", "options": [$options] }""")))
        val effects = (1..ViewLimits.MAX_LITERAL_LIST + 1).joinToString(",") { """{ "op": "clear", "path": "a" }""" }
        assertEquals(listOf(DiagnosticCode.VIEW_LIMIT), codes(root("""{ "type": "button", "label": "L", "action": "demo.go", "before": [$effects] }""")))
    }

    // ---- icons

    private fun iconErrors(icon: String): List<Diagnostic> {
        val manifest = Manifests.minimal(""""contributes": { "viewsContainers": { "sidebar": [{ "id": "acme.demo.box", "title": "Box", "icon": "./i.svg" }] } }, "easyide": { "capabilities": ["ui.contribute"] }""")
        return (Manifests.parse(manifest, mapOf("i.svg" to icon)) as? ParseResult.Invalid)?.errors.orEmpty()
    }

    @Test fun `pack icons must be one-colour vectors on the 24 grid`() {
        assertEquals(emptyList<Diagnostic>(), iconErrors(svg))
        assertEquals(listOf(DiagnosticCode.ICON_RULE), iconErrors(svg.replace("0 0 24 24", "0 0 16 16")).map { it.code })
        assertEquals(listOf(DiagnosticCode.ICON_RULE), iconErrors(svg.replace("viewBox=\"0 0 24 24\"", "")).map { it.code })
        val twoColours = svg.replace("<path fill=\"currentColor\"", "<path fill=\"#f00\"/><path fill=\"#0f0\"")
        assertEquals(listOf(DiagnosticCode.ICON_RULE), iconErrors(twoColours).map { it.code })
        assertEquals(listOf(DiagnosticCode.ICON_RULE), iconErrors(svg.replace("<path", "<image href=\"x.png\"/><path")).map { it.code })
        assertEquals(listOf(DiagnosticCode.ICON_RULE), iconErrors(svg.replace("<path", "<script>1</script><path")).map { it.code })
        assertEquals(listOf(DiagnosticCode.ICON_RULE), iconErrors(svg + " ".repeat(ViewLimits.ICON_MAX_BYTES)).map { it.code })
    }

    @Test fun `a raster icon file is refused`() {
        val manifest = Manifests.minimal(""""contributes": { "viewsContainers": { "sidebar": [{ "id": "acme.demo.box", "title": "Box", "icon": "./i.png" }] } }, "easyide": { "capabilities": ["ui.contribute"] }""")
        assertEquals(listOf(DiagnosticCode.PATH_INVALID), Manifests.errors(manifest, mapOf("i.png" to "x")).map { it.code })
    }
}
