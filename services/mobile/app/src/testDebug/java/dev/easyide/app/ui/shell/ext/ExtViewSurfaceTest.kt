package dev.easyide.app.ui.shell.ext

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.easyide.app.extensions.ExtFixtures
import dev.easyide.app.ui.shell.host.LocalShellActions
import dev.easyide.app.ui.shell.host.ShellActions
import dev.easyide.app.ui.theme.EasyIdeTheme
import dev.easyide.app.ui.theme.ThemeMode
import dev.easyide.extensions.action.Action
import dev.easyide.extensions.action.OpenGroup
import dev.easyide.extensions.view.ViewDocument
import dev.easyide.extensions.whenclause.ContextLookup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An extension view on screen under Robolectric: the schema draws through the kit, controls carry a role and a name, events
 * reach the command with their args, a `confirm` asks first, a view over its limits says so, and the composer's message shows
 * in the conversation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ExtViewSurfaceTest {
    @get:Rule val compose = createComposeRule()

    private class Calls : ViewCalls {
        val commands = ArrayList<Pair<String, JsonElement?>>()
        val opened = ArrayList<String>()
        var reply: CallResult = CallResult.Done
        override suspend fun command(owner: String, id: String, args: JsonElement?): CallResult { commands += id to args; return reply }
        override suspend fun inline(owner: String, title: String, action: Action, args: JsonElement?): CallResult = reply
        override fun open(uri: String, group: OpenGroup): Boolean { opened += uri; return true }
    }

    private val calls = Calls()
    private val hub = ViewDataHub()
    private val toasts = ArrayList<String>()

    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="currentColor" d="M4 4h16v16H4z"/></svg>"""

    /** A view document through the real parser. */
    private fun doc(root: String, state: String = "{}"): ViewDocument {
        val manifest = ExtFixtures.manifest("""
            "contributes": { "commands": [{ "command": "acme.demo.go", "title": "Go" }, { "command": "acme.demo.send", "title": "Send" }],
              "viewsContainers": { "sidebar": [{ "id": "acme.demo.box", "title": "Box", "icon": "./i.svg" }] },
              "views": { "acme.demo.box": [{ "id": "acme.demo.v", "name": "V", "schema": "./v.json" }] } },
            "easyide": { "capabilities": ["ui.contribute"],
              "actions": { "acme.demo.go": { "type": "showMessage", "text": "go" }, "acme.demo.send": { "type": "showMessage", "text": "send" } },
              "documents": [{ "type": "acme.demo/note", "title": "N", "icon": "box", "schema": "./v.json" }] }""")
        val d = ExtFixtures.descriptor(manifest, mapOf("i.svg" to svg, "v.json" to """{ "viewSchema": 1, "state": $state, "root": $root }"""))
        return d.contributes.views.single().schema!!
    }

    private fun show(doc: ViewDocument, key: String = "acme.demo.v") {
        val host = ExtViewHost(hub, ViewEvents(hub, calls), MutableStateFlow(ContextLookup { null }), { _, _ -> })
        compose.setContent {
            EasyIdeTheme(themeMode = ThemeMode.DARK) {
                CompositionLocalProvider(LocalShellActions provides ShellActions({}, {}, {}, { toasts += it })) {
                    ExtViewSurface(doc, key, "acme.demo", "Demo", host)
                }
            }
        }
    }

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun `rows draw their text and a control is a named button that runs its command with its args`() {
        hub.edit("acme.demo.v", JsonObject(emptyMap())) { obj("""{ "rows": [{ "id": "a1", "name": "web", "state": "running" }, { "id": "b2", "name": "db", "state": "exited" }] }""") }
        show(doc("""{ "type": "list", "bind": "rows", "key": "id", "item": { "type": "row", "gap": "s", "children": [
            { "type": "text", "value": "{name}" },
            { "type": "iconButton", "icon": "play", "label": "Start {name}", "when": "state != running", "action": "acme.demo.go", "args": { "id": "{id}" } },
            { "type": "iconButton", "icon": "stop", "label": "Stop {name}", "when": "state == running", "action": "acme.demo.go", "args": { "id": "{id}" } }] } }"""))
        compose.onNodeWithText("web").assertIsDisplayed()
        compose.onNodeWithText("db").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop web").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        compose.onNodeWithContentDescription("Start web").assertDoesNotExist()
        compose.onNodeWithContentDescription("Start db").performClick()
        compose.waitForIdle()
        assertEquals(listOf("acme.demo.go" to obj("""{ "id": "b2" }""")), calls.commands)
    }

    @Test fun `a control with confirm asks first, and only Continue runs it`() {
        show(doc("""{ "type": "button", "label": "Delete", "style": "danger", "action": "acme.demo.go", "confirm": { "title": "Delete it?", "body": "It is gone for good.", "destructive": true } }"""))
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete it?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue(calls.commands.isEmpty())
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        assertEquals(1, calls.commands.size)
    }

    @Test fun `a row with open goes to the document, and a failure is told once`() {
        hub.edit("acme.demo.v", JsonObject(emptyMap())) { obj("""{ "n": "n1" }""") }
        show(doc("""{ "type": "column", "children": [
            { "type": "row", "open": "ext://acme.demo/note/{n}", "children": [{ "type": "text", "value": "Open note" }] },
            { "type": "button", "label": "Fail", "action": "acme.demo.go" }] }"""))
        compose.onNodeWithText("Open note").performClick()
        compose.waitForIdle()
        assertEquals(listOf("ext://acme.demo/note/n1"), calls.opened)
        calls.reply = CallResult.Failed("no such container")
        compose.onNodeWithText("Fail").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Demo failed: no such container"), toasts)
    }

    @Test fun `a view over the render budget says why instead of drawing`() {
        val template = "{ \"type\": \"column\", \"children\": [" + (1..39).joinToString(",") { """{ "type": "text", "value": "x" }""" } + "] }"
        show(doc("""{ "type": "row", "children": [{ "type": "list", "bind": "r", "item": $template }] }""", """{ "r": [${(1..100).joinToString(",") { """{ "id": $it }""" }}] }"""))
        compose.onNodeWithText("View unavailable", substring = true).assertIsDisplayed()
    }

    @Test fun `the composer sends its text, the message shows and the reply is appended`() {
        calls.reply = CallResult.Value(obj("""{ "role": "assistant", "text": "pong" }"""))
        show(doc("""{ "type": "column", "children": [
            { "type": "chat", "bind": "messages", "follow": true },
            { "type": "composer", "bind": "draft", "hint": "Say something", "sendLabel": "Send", "action": "acme.demo.send", "args": { "text": "{draft}" },
              "before": [{ "op": "append", "path": "messages", "value": { "role": "user", "text": "{draft}" } }, { "op": "clear", "path": "draft" }],
              "as": "messages", "mode": "append" }] }""", """{ "messages": [], "draft": "" }"""))
        compose.onNode(hasSetTextAction()).performTextInput("ping")
        compose.onNodeWithText("Send").performClick()
        compose.waitForIdle()
        assertEquals(listOf("acme.demo.send" to obj("""{ "text": "ping" }""")), calls.commands)
        compose.onNodeWithText("ping").assertIsDisplayed()
        compose.onNodeWithText("pong").assertIsDisplayed()
    }

    @Test fun `a disabled button is not enabled and a status dot is named for a screen reader`() {
        show(doc("""{ "type": "column", "children": [
            { "type": "button", "label": "Go", "action": "acme.demo.go", "enabledWhen": "ready" },
            { "type": "statusDot", "tone": "success", "label": "running" }] }""", """{ "ready": false }"""))
        compose.onNodeWithText("Go").assertIsNotEnabled()
        compose.onNodeWithContentDescription("running").assertIsDisplayed()
    }
}
