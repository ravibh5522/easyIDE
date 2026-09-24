package dev.easyide.app.ui.shell.ext

import dev.easyide.app.extensions.ExtFixtures
import dev.easyide.app.extensions.adapters.ShellContributions
import dev.easyide.extensions.action.Action
import dev.easyide.extensions.action.OpenGroup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Data is fetched only while a view is on screen, at the interval it asked for, and a failing source speaks once. */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewDataDriverTest {
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="currentColor" d="M4 4h16v16H4z"/></svg>"""
    private val view = """{ "viewSchema": 1, "state": { "n": 0 }, "root": { "type": "text", "value": "{n}" } }"""

    private val pack = ExtFixtures.descriptor(ExtFixtures.manifest("""
        "contributes": { "commands": [{ "command": "acme.demo.state", "title": "State" }],
          "viewsContainers": { "sidebar": [{ "id": "acme.demo.main", "title": "Box", "icon": "./i.svg" }] },
          "views": { "acme.demo.main": [{ "id": "acme.demo.list", "name": "List", "schema": "./v.json" }] } },
        "easyide": { "capabilities": ["ui.contribute", "ui.stage", "sandbox.exec"],
          "actions": { "acme.demo.state": { "type": "sandboxExec", "command": ["echo"], "output": "capture" } },
          "navigation": [{ "id": "acme.demo.nav", "title": "Box", "icon": "box", "target": { "container": "acme.demo.main" }, "badge": { "view": "acme.demo.list", "path": "n" } }],
          "documents": [{ "type": "acme.demo/box", "title": "{name}", "icon": "box", "schema": "./v.json", "state": { "provider": "acme.demo.state", "intervalSec": 4 } }],
          "viewData": { "acme.demo.list": { "kind": "object", "from": { "type": "sandboxExec", "command": ["echo"], "output": "capture" }, "intervalSec": 2 } } }
    """), mapOf("i.svg" to svg, "v.json" to view))

    private class Calls : ViewCalls {
        val inlineRuns = ArrayList<String>()
        val commandRuns = ArrayList<Pair<String, JsonElement?>>()
        var next: () -> CallResult = { CallResult.Value(Json.parseToJsonElement("""{ "exitCode": 0, "stdout": "{\"n\": 7}", "stderr": "" }""")) }
        override suspend fun command(owner: String, id: String, args: JsonElement?): CallResult { commandRuns += id to args; return next() }
        override suspend fun inline(owner: String, title: String, action: Action, args: JsonElement?): CallResult { inlineRuns += title; return next() }
        override fun open(uri: String, group: OpenGroup) = true
    }

    private val calls = Calls()
    private val hub = ViewDataHub()
    private val logs = ArrayList<String>()
    private val shell = MutableStateFlow(ShellContributions.of(ExtFixtures.snapshot(pack)))

    private fun TestScope.driver() = ViewDataDriver(shell, hub, calls, { _, m -> logs += m }, backgroundScope)

    @Test fun `a shown view is fetched at once and on its interval until hidden`() = runTest(StandardTestDispatcher()) {
        val driver = driver()
        runCurrent()
        // The badge's view was fetched once on its own, before anything was shown.
        assertEquals(1, calls.inlineRuns.size)
        driver.showView("acme.demo.list")
        runCurrent()
        assertEquals(2, calls.inlineRuns.size)
        assertEquals(kotlinx.serialization.json.JsonPrimitive(7), hub.data("acme.demo.list", JsonObject(emptyMap()))["n"])
        advanceTimeBy(2_001); runCurrent()
        assertEquals(3, calls.inlineRuns.size)
        driver.hideView("acme.demo.list")
        advanceTimeBy(10_000); runCurrent()
        assertEquals(3, calls.inlineRuns.size)
    }

    @Test fun `two surfaces of one view share one loop and it stops with the last`() = runTest(StandardTestDispatcher()) {
        val driver = driver(); runCurrent()
        calls.inlineRuns.clear()
        driver.showView("acme.demo.list"); driver.showView("acme.demo.list"); runCurrent()
        assertEquals(1, calls.inlineRuns.size)
        driver.hideView("acme.demo.list")
        advanceTimeBy(2_001); runCurrent()
        assertEquals(2, calls.inlineRuns.size)
        driver.hideView("acme.demo.list")
        advanceTimeBy(20_000); runCurrent()
        assertEquals(2, calls.inlineRuns.size)
    }

    @Test fun `a document's provider gets its uri and key and merges into the document data`() = runTest(StandardTestDispatcher()) {
        val driver = driver(); runCurrent()
        driver.showDocument("acme.demo/box", "ext://acme.demo/box/n1", "n1"); runCurrent()
        val (id, args) = calls.commandRuns.single()
        assertEquals("acme.demo.state", id)
        assertEquals(Json.parseToJsonElement("""{ "uri": "ext://acme.demo/box/n1", "key": "n1" }"""), args)
        val data = hub.data("ext://acme.demo/box/n1", JsonObject(emptyMap()))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(7), data["n"])
        advanceTimeBy(4_001); runCurrent()
        assertEquals(2, calls.commandRuns.size)
        driver.hideDocument("ext://acme.demo/box/n1")
        advanceTimeBy(20_000); runCurrent()
        assertEquals(2, calls.commandRuns.size)
    }

    @Test fun `a failing source is logged once, not every interval, and speaks again when the reason changes`() = runTest(StandardTestDispatcher()) {
        calls.next = { CallResult.Failed("docker: not found") }
        val driver = driver(); runCurrent()
        driver.showView("acme.demo.list"); runCurrent()
        advanceTimeBy(6_100); runCurrent()
        // The badge's first fetch and every interval after it failed the same way: one line.
        assertEquals(1, logs.size)
        assertTrue(logs.single(), logs.single().contains("docker: not found"))
        calls.next = { CallResult.Failed("permission denied") }
        advanceTimeBy(2_100); runCurrent()
        assertEquals(2, logs.size)
        driver.hideView("acme.demo.list")
    }

    @Test fun `showing a view with no source or a document with no provider does nothing`() = runTest(StandardTestDispatcher()) {
        val driver = driver(); runCurrent()
        calls.inlineRuns.clear()
        driver.showView("acme.demo.unknown")
        driver.showDocument("acme.demo/none", "ext://acme.demo/none/x", "x")
        runCurrent()
        assertTrue(calls.inlineRuns.isEmpty() && calls.commandRuns.isEmpty())
        driver.hideView("acme.demo.unknown")
    }
}
