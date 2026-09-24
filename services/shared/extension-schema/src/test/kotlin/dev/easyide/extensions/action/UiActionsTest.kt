package dev.easyide.extensions.action

import dev.easyide.extensions.FakeSettings
import dev.easyide.extensions.Manifests
import dev.easyide.extensions.manifest.DiagnosticCode
import dev.easyide.extensions.whenclause.ContextKeyService
import dev.easyide.extensions.whenclause.ContextKeys
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two action-language additions of the view schema: `${arg:...}` and `openDocument`. */
class UiActionsTest {
    private val host = FakeHost()
    private val settings = FakeSettings()
    private val keys = ContextKeyService(settings).apply { set(ContextKeys.envState, "ready") }
    private val bindings = HashMap<String, CommandBinding>()
    private val runner = ActionRunner({ bindings[it] }, host, settings, { keys.snapshot.value }, {}) { _, _, _ -> CommandOutcome.NotFound }

    private fun pack(action: String, caps: String) {
        val manifest = Manifests.minimal("""
            "contributes": { "commands": [{ "command": "demo.go", "title": "Go" }] },
            "easyide": { "capabilities": [$caps], "actions": { "demo.go": $action } }""").replace("#{", "\${")
        val d = Manifests.ok(manifest)
        bindings["demo.go"] = CommandBinding(d.id, "demo.go", "Go", CommandHandler.Declarative(d.actions.getValue("demo.go")), d.inputs, d.capabilities, emptySet(), d.guestRoot)
    }

    private fun args(s: String) = Json.parseToJsonElement(s)

    @Test fun `arg variables read the invocation args, by path`() = runTest {
        pack("""{ "type": "sandboxExec", "command": ["docker", "start", "#{arg:id}", "#{arg:opts.name}"], "output": "capture" }""", "\"sandbox.exec\"")
        assertTrue(runner.run("demo.go", args("""{ "id": "9f2c", "opts": { "name": "web" } }""")) is ActionOutcome.Done)
        assertEquals(listOf("docker", "start", "9f2c", "web"), host.execs.single().argv)
    }

    @Test fun `an arg with shell syntax stays one argv element and one quoted shell word`() = runTest {
        pack("""{ "type": "sandboxExec", "command": ["echo", "#{arg:text}"], "output": "capture" }""", "\"sandbox.exec\"")
        runner.run("demo.go", args("""{ "text": "a; rm -rf / $(x)" }"""))
        assertEquals(listOf("echo", "a; rm -rf / $(x)"), host.execs.single().argv)
        pack("""{ "type": "runInTerminal", "command": "echo #{arg:text}" }""", "\"sandbox.exec\"")
        runner.run("demo.go", args("""{ "text": "it's; rm" }"""))
        assertEquals("cd '/workspace' && echo 'it'\\''s; rm'", host.terminal.single().commandLine)
    }

    @Test fun `a missing or null arg fails the step with E_ARGS`() = runTest {
        pack("""{ "type": "sandboxExec", "command": ["echo", "#{arg:id}"], "output": "capture" }""", "\"sandbox.exec\"")
        val none = runner.run("demo.go") as ActionOutcome.Failed
        assertEquals(ActionError.ARGS, none.error)
        val nul = runner.run("demo.go", args("""{ "id": null }""")) as ActionOutcome.Failed
        assertEquals(ActionError.ARGS, nul.error)
        assertTrue(host.execs.isEmpty())
    }

    @Test fun `arg names are validated at load`() {
        val bad = Manifests.minimal("""
            "contributes": { "commands": [{ "command": "demo.go", "title": "Go" }] },
            "easyide": { "actions": { "demo.go": { "type": "showMessage", "text": "x #{arg:a b}" } } }""".replace("#{", "\${"))
        assertEquals(DiagnosticCode.TEMPLATE, Manifests.errors(bad).single().code)
    }

    @Test fun `openDocument opens the packs own document in the requested group`() = runTest {
        pack("""{ "type": "openDocument", "uri": "ext://acme.demo/note/#{arg:id}", "group": "beside", "preview": false }""", "\"ui.stage\"")
        assertEquals(ActionOutcome.Done(JsonNull), runner.run("demo.go", args("""{ "id": "n1" }""")))
        assertEquals(listOf("ext://acme.demo/note/n1" to OpenGroup.BESIDE), host.documents)
    }

    @Test fun `openDocument refuses another extensions document and reports an unopenable one`() = runTest {
        pack("""{ "type": "openDocument", "uri": "ext://other.pack/note/1" }""", "\"ui.stage\"")
        val foreign = runner.run("demo.go") as ActionOutcome.Failed
        assertEquals(ActionError.CAPABILITY, foreign.error)
        assertTrue(host.documents.isEmpty())
        pack("""{ "type": "openDocument", "uri": "ext://acme.demo/note/1" }""", "\"ui.stage\"")
        host.documentsOpen = false
        assertEquals(ActionError.UNAVAILABLE, (runner.run("demo.go") as ActionOutcome.Failed).error)
    }

    @Test fun `openDocument needs ui stage declared`() {
        val manifest = Manifests.minimal("""
            "contributes": { "commands": [{ "command": "demo.go", "title": "Go" }] },
            "easyide": { "actions": { "demo.go": { "type": "openDocument", "uri": "ext://acme.demo/n/1" } } }""")
        val e = Manifests.errors(manifest).single()
        assertEquals(DiagnosticCode.CAP_UNDECLARED, e.code)
        assertTrue(e.message, e.message.contains("ui.stage"))
    }
}
