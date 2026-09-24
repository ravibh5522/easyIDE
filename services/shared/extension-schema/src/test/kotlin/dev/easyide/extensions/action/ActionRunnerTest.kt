package dev.easyide.extensions.action

import dev.easyide.extensions.FakeSettings
import dev.easyide.extensions.Manifests
import dev.easyide.extensions.capability.Capability
import dev.easyide.extensions.capability.CapabilitySet
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.settings.ConfigTarget
import dev.easyide.extensions.whenclause.ContextKeyService
import dev.easyide.extensions.whenclause.ContextKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActionRunnerTest {
    private val host = FakeHost()
    private val settings = FakeSettings()
    private val keys = ContextKeyService(settings).apply { set(ContextKeys.envState, "ready") }
    private val log = ArrayList<LogEntry>()
    private val bindings = HashMap<String, CommandBinding>()
    private val runner = ActionRunner({ bindings[it] }, host, settings, { keys.snapshot.value }, { log += it }) { _, id, _ ->
        CommandOutcome.Done(JsonPrimitive("wasm:$id"))
    }

    /** Declares `demo.<id>` commands bound to [actions] (`#{x}` = `${x}`) and registers their bindings. */
    private fun pack(actions: Map<String, String>, caps: String = "", config: String = "", inputs: String = "[]", name: String = "demo"): ExtensionDescriptor {
        val commands = actions.keys.joinToString(",") { """{ "command": "$name.$it", "title": "T-$it" }""" }
        val acts = actions.entries.joinToString(",") { (k, v) -> "\"$name.$k\": $v" }
        val manifest = Manifests.minimal("""
            "contributes": { "commands": [$commands] ${if (config.isEmpty()) "" else ", \"configuration\": { \"properties\": { $config } }"} },
            "easyide": { "capabilities": [$caps], "actions": { $acts }, "inputs": $inputs }""", name = name).replace("#{", "\${")
        val d = Manifests.ok(manifest)
        d.actions.forEach { (cmd, action) ->
            bindings[cmd] = CommandBinding(d.id, cmd, "T-" + cmd.substringAfter('.'), CommandHandler.Declarative(action), d.inputs,
                d.capabilities, d.contributes.configuration.mapTo(HashSet()) { it.key }, d.guestRoot)
        }
        return d
    }

    private fun grant(cmd: String, caps: CapabilitySet) { bindings[cmd] = bindings[cmd]!!.copy(granted = caps) }
    private fun json(s: String) = Json.parseToJsonElement(s)

    @Test fun `runInTerminal quotes every substituted value into one shell word (R-SEC-15)`() = runTest {
        host.editor = host.editor!!.copy(path = "/workspace/src/it's \$(rm -rf ~) `x` *.py")
        pack(mapOf("run" to """{ "type": "runInTerminal", "command": "python3 #{file} --flag", "env": { "PY": "#{config:demo.py}" }, "terminal": "Py" }"""),
            caps = "\"sandbox.exec\"", config = "\"demo.py\": { \"type\": \"string\" }")
        settings.set("demo.py", "\"python3 -X dev\"")
        assertEquals(ActionOutcome.Done(JsonNull), runner.run("demo.run"))
        val r = host.terminal.single()
        assertEquals("cd '/workspace' && PY='python3 -X dev' python3 '/workspace/src/it'\\''s \$(rm -rf ~) `x` *.py' --flag", r.commandLine)
        assertEquals("Py", r.terminalName)
        assertTrue(r.focus)
    }

    @Test fun `sandboxExec passes argv unquoted, captures and chains results`() = runTest {
        host.execResult = { ExecOutcome.Exited(0, "/workspace/a/bin/python\n/workspace/b/bin/python\n", "", false) }
        host.pickAnswer = { r -> listOf(r.items[1].value) }
        pack(mapOf("pick" to """{ "type": "sequence", "steps": [
            { "type": "sandboxExec", "command": ["sh", "-c", "ls #{fileDirname}"], "cwd": "#{workspaceFolder}/src", "output": "capture", "as": "venvs" },
            { "type": "showQuickPick", "id": "venv", "itemsFrom": "#{result:venvs}" },
            { "type": "setConfig", "key": "demo.interpreter", "value": "#{input:venv}", "target": "project" },
            { "type": "runInTerminal", "command": "echo #{result:venvs}" } ] }"""),
            caps = "\"sandbox.exec\"", config = "\"demo.interpreter\": { \"type\": \"string\" }")
        val out = runner.run("demo.pick")
        assertTrue(out.toString(), out is ActionOutcome.Done)
        val exec = host.execs.single()
        assertEquals(listOf("sh", "-c", "ls /workspace/src"), exec.argv)
        assertEquals("/workspace/src", exec.cwd)
        assertEquals(600_000L, exec.timeoutMs)
        assertEquals(1024 * 1024, exec.captureLimitBytes)
        assertEquals(listOf("/workspace/a/bin/python", "/workspace/b/bin/python"), host.picks.single().items.map { it.label })
        assertEquals(Triple("demo.interpreter", JsonPrimitive("/workspace/b/bin/python"), ConfigTarget.PROJECT), settings.writes.single())
        assertEquals("cd '/workspace' && echo '/workspace/a/bin/python\n/workspace/b/bin/python'", host.terminal.single().commandLine)
    }

    @Test fun `a failing step aborts the sequence, is logged with its path, continueOnError goes on`() = runTest {
        host.fileExists = false
        pack(mapOf(
            "stop" to """{ "type": "sequence", "steps": [ { "type": "openFile", "path": "gone.txt" }, { "type": "showMessage", "text": "after" } ] }""",
            "go" to """{ "type": "sequence", "continueOnError": true, "steps": [ { "type": "openFile", "path": "gone.txt" }, { "type": "showMessage", "text": "after" } ] }""",
        ), caps = "\"fs.project(read)\"")
        val failed = runner.run("demo.stop") as ActionOutcome.Failed
        assertEquals("sequence.steps[0].openFile", failed.stepPath)
        assertEquals(ActionError.NOT_FOUND, failed.error)
        assertTrue(host.messages.isEmpty())
        assertTrue(log.single().message.contains("sequence.steps[0].openFile"))
        assertEquals(listOf("/workspace/gone.txt"), host.opened)
        assertEquals(ActionOutcome.Done(JsonNull), runner.run("demo.go"))
        assertEquals("after", host.messages.single().text)
    }

    @Test fun `a dismissed prompt cancels silently`() = runTest {
        host.inputAnswer = { null }
        pack(mapOf("ask" to """{ "type": "sequence", "steps": [ { "type": "showInputBox", "id": "n" }, { "type": "showMessage", "text": "#{input:n}" } ] }"""))
        assertEquals(ActionOutcome.Cancelled, runner.run("demo.ask"))
        assertTrue(log.isEmpty())
        assertTrue(host.messages.isEmpty())
    }

    @Test fun `password input is redacted from logs (R-SEC-17)`() = runTest {
        host.inputAnswer = { "hunter2" }
        pack(mapOf("pw" to """{ "type": "sequence", "steps": [ { "type": "showInputBox", "id": "p", "password": true }, { "type": "openFile", "path": "/etc/#{input:p}" } ] }"""),
            caps = "\"fs.project(read)\"")
        val failed = runner.run("demo.pw") as ActionOutcome.Failed
        assertEquals(ActionError.CAPABILITY, failed.error)
        assertTrue(failed.message, !failed.message.contains("hunter2") && failed.message.contains("***"))
        assertTrue(log.none { it.message.contains("hunter2") })
    }

    @Test fun `run-time capability checks - granted set, outside project, foreign and protected settings`() = runTest {
        pack(mapOf(
            "exec" to """{ "type": "sandboxExec", "command": ["true"], "output": "silent" }""",
            "open" to """{ "type": "openFile", "path": "../other/x" }""",
            "mine" to """{ "type": "setConfig", "key": "demo.x", "value": 1, "target": "user" }""",
            "foreign" to """{ "type": "setConfig", "key": "editor.tabSize", "value": 2, "target": "user" }""",
            "protected" to """{ "type": "toggleConfig", "key": "extensions.enabled" }""",
        ), caps = "\"sandbox.exec\", \"fs.project(read)\"", config = "\"demo.x\": { \"type\": \"integer\" }")
        grant("demo.exec", CapabilitySet.EMPTY)
        assertEquals(ActionError.CAPABILITY, (runner.run("demo.exec") as ActionOutcome.Failed).error)
        assertTrue(host.execs.isEmpty())
        assertEquals(ActionError.CAPABILITY, (runner.run("demo.open") as ActionOutcome.Failed).error)
        grant("demo.open", CapabilitySet.of(Capability.FsProject(false), Capability.FsOutsideProject))
        assertEquals(ActionOutcome.Done(JsonNull), runner.run("demo.open"))
        assertEquals("/other/x", host.opened.single())
        assertEquals(ActionOutcome.Done(JsonNull), runner.run("demo.mine"))
        assertEquals(ActionError.CAPABILITY, (runner.run("demo.foreign") as ActionOutcome.Failed).error)
        grant("demo.foreign", CapabilitySet.of(Capability.UiSettings))
        assertEquals(ActionOutcome.Done(JsonNull), runner.run("demo.foreign"))
        grant("demo.protected", CapabilitySet.of(Capability.UiSettings))
        assertEquals(ActionError.CAPABILITY, (runner.run("demo.protected") as ActionOutcome.Failed).error)
        assertEquals(listOf("demo.x", "editor.tabSize"), settings.writes.map { it.first })
    }

    @Test fun `executeCommand runs the target with the target owner's grants`() = runTest {
        pack(mapOf("caller" to """{ "type": "executeCommand", "command": "other.exec", "args": { "file": "#{file}" } }"""))
        pack(mapOf("exec" to """{ "type": "sandboxExec", "command": ["true"], "output": "silent" }"""), caps = "\"sandbox.exec\"", name = "other")
        assertTrue(runner.run("demo.caller") is ActionOutcome.Done)
        assertEquals(1, host.execs.size)
        grant("other.exec", CapabilitySet.EMPTY)
        val failed = runner.run("demo.caller") as ActionOutcome.Failed
        assertEquals(ActionError.CAPABILITY, failed.error)
        assertEquals(ActionOutcome.Done(JsonNull), runner.run("workbench.action.files.save"))
        assertEquals("workbench.action.files.save", host.builtIns.single().first)
    }

    @Test fun `command variables nest with a depth bound and cycles fail`() = runTest {
        pack(mapOf(
            "outer" to """{ "type": "showMessage", "text": "v=#{command:demo.inner}" }""",
            "inner" to """{ "type": "toggleConfig", "key": "demo.flag" }""",
            "loop" to """{ "type": "showMessage", "text": "#{command:demo.loop}" }""",
        ), config = "\"demo.flag\": { \"type\": \"boolean\" }")
        assertTrue(runner.run("demo.outer") is ActionOutcome.Done)
        assertEquals("v=true", host.messages.single().text)
        val loop = runner.run("demo.loop") as ActionOutcome.Failed
        assertTrue(loop.message, loop.message.contains("already running"))
    }

    @Test fun `unresolvable variables fail the step, env comes from the environment shell only (R-SEC-04)`() = runTest {
        pack(mapOf(
            "env" to """{ "type": "showMessage", "text": "#{env:VIRTUAL_ENV}" }""",
            "noenv" to """{ "type": "showMessage", "text": "#{env:PATH}" }""",
            "nocfg" to """{ "type": "showMessage", "text": "#{config:demo.unset}" }""",
        ))
        runner.run("demo.env")
        assertEquals("/workspace/.venv", host.messages.single().text)
        assertEquals(ActionError.ARGS, (runner.run("demo.noenv") as ActionOutcome.Failed).error)
        assertEquals(ActionError.ARGS, (runner.run("demo.nocfg") as ActionOutcome.Failed).error)
        host.editor = null
        pack(mapOf("file" to """{ "type": "showMessage", "text": "#{file}" }"""))
        assertEquals(ActionError.UNAVAILABLE, (runner.run("demo.file") as ActionOutcome.Failed).error)
    }

    @Test fun `all predefined variables resolve from the frozen editor and workspace`() = runTest {
        pack(mapOf("vars" to """{ "type": "showMessage", "text": "#{workspaceFolder}|#{workspaceFolderBasename}|#{file}|#{relativeFile}|#{fileBasename}|#{fileBasenameNoExtension}|#{fileExtname}|#{fileDirname}|#{relativeFileDirname}|#{fileWorkspaceFolder}|#{lineNumber}|#{column}|#{selectedText}|#{currentWord}|#{lineText}|#{languageId}|#{cwd}|#{pathSeparator}|#{extensionPath}|#{envId}|#{envName}" }"""))
        runner.run("demo.vars")
        assertEquals(
            "/workspace|demo|/workspace/src/my file.py|src/my file.py|my file.py|my file|.py|/workspace/src|src|/workspace|3|7|sel|word|line text|python|/workspace|/|/opt/easyide/extensions/acme.demo|env1|Debian",
            host.messages.single().text,
        )
    }

    @Test fun `argv elements with NUL fail, environment must be ready`() = runTest {
        host.shellEnv = mapOf("BAD" to "a\u0000b")
        pack(mapOf("nul" to """{ "type": "sandboxExec", "command": ["echo", "#{env:BAD}"], "output": "silent" }"""), caps = "\"sandbox.exec\"")
        assertEquals(ActionError.ARGS, (runner.run("demo.nul") as ActionOutcome.Failed).error)
        keys.set(ContextKeys.envState, "stopped")
        host.shellEnv = emptyMap()
        pack(mapOf("t" to """{ "type": "runInTerminal", "command": "make" }"""), caps = "\"sandbox.exec\"")
        assertEquals(ActionError.UNAVAILABLE, (runner.run("demo.t") as ActionOutcome.Failed).error)
        assertTrue(host.execs.isEmpty() && host.terminal.isEmpty())
    }

    @Test fun `timeouts and spawn failures map to typed errors`() = runTest {
        pack(mapOf("slow" to """{ "type": "sandboxExec", "command": ["sleep", "9"], "timeoutSec": 2, "output": "capture" }"""), caps = "\"sandbox.exec\"")
        host.execResult = { ExecOutcome.TimedOut("partial err") }
        val t = runner.run("demo.slow") as ActionOutcome.Failed
        assertEquals(ActionError.TIMEOUT, t.error)
        assertEquals("partial err", t.stderrTail)
        assertEquals(2000L, host.execs.single().timeoutMs)
        host.execResult = { ExecOutcome.Unavailable("env stopped") }
        assertEquals(ActionError.UNAVAILABLE, (runner.run("demo.slow") as ActionOutcome.Failed).error)
    }

    @Test fun `single flight per command, snapshot frozen at invocation`() = runTest(UnconfinedTestDispatcher()) {
        pack(mapOf("slow" to """{ "type": "sequence", "steps": [
            { "type": "sandboxExec", "command": ["x"], "output": "silent" }, { "type": "showMessage", "text": "#{file}" } ] }"""), caps = "\"sandbox.exec\"")
        host.execGate = CompletableDeferred()
        val first = async { runner.run("demo.slow") }
        val second = runner.run("demo.slow") as ActionOutcome.Failed
        assertTrue(second.message.contains("T-slow is already running"))
        host.editor = host.editor!!.copy(path = "/workspace/other.py")
        host.execGate!!.complete(Unit)
        assertTrue(first.await() is ActionOutcome.Done)
        assertEquals("/workspace/src/my file.py", host.messages.single().text)
    }

    @Test fun `openUrl requires https and a confirmation`() = runTest {
        pack(mapOf("docs" to """{ "type": "openUrl", "url": "https://docs.example.com/#{languageId}" }""", "bad" to """{ "type": "openUrl", "url": "http://example.com" }"""))
        assertEquals(ActionOutcome.Done(JsonNull), runner.run("demo.docs"))
        assertEquals("https://docs.example.com/python", host.urls.single())
        assertEquals(ActionError.ARGS, (runner.run("demo.bad") as ActionOutcome.Failed).error)
        host.confirmUrlAnswer = false
        assertEquals(ActionOutcome.Cancelled, runner.run("demo.docs"))
        assertEquals(1, host.urls.size)
    }

    @Test fun `applyEdit resolves paths and checks workspace edit URIs`() = runTest {
        pack(mapOf(
            "text" to """{ "type": "applyEdit", "edits": [{ "range": { "start": {"line":0,"character":0}, "end": {"line":0,"character":1} }, "text": "#{selectedText}!" }] }""",
            "ws" to """{ "type": "applyEdit", "edits": { "changes": { "file:///etc/passwd": [] } } }""",
        ), caps = "\"fs.project(write)\"")
        assertEquals(ActionOutcome.Done(JsonPrimitive(true)), runner.run("demo.text"))
        assertEquals(ResolvedTextEdit("/workspace/src/my file.py", TextRange(TextPosition(0, 0), TextPosition(0, 1)), "sel!"), host.edits.single().single())
        assertEquals(ActionError.CAPABILITY, (runner.run("demo.ws") as ActionOutcome.Failed).error)
        assertTrue(host.workspaceEdits.isEmpty())
    }

    @Test fun `remaining vocabulary against the fake host`() = runTest {
        host.messageAnswer = { "Open" }
        host.lspOutcome = LspOutcome.Result(json("""[{"uri":"file:///workspace/a.py"}]"""))
        pack(mapOf(
            "task" to """{ "type": "runTask", "task": "build #{envId}" }""",
            "snip" to """{ "type": "insertSnippet", "snippet": "print(#{selectedText})" }""",
            "lsp" to """{ "type": "lspRequest", "method": "textDocument/definition", "then": "showLocations", "as": "loc" }""",
            "msg" to """{ "type": "showMessage", "text": "hi", "severity": "warning", "actions": [{ "title": "Open", "action": { "type": "revealStage", "stage": "bottom" } }] }""",
            "pick" to """{ "type": "showQuickPick", "id": "p", "canPickMany": true, "items": [{ "label": "A", "value": 1 }, { "label": "B" }] }""",
            "toggle" to """{ "type": "toggleConfig", "key": "demo.mode", "values": ["a", "b", "c"] }""",
            "input" to """{ "type": "showMessage", "text": "#{input:who} #{input:who} #{input:kind}" }""",
        ), caps = "\"sandbox.exec\", \"lsp.request\"", config = "\"demo.mode\": { \"type\": \"string\" }",
            inputs = """[{ "id": "who", "type": "promptString", "default": "me" }, { "id": "kind", "type": "pickString", "options": ["x", { "label": "Why", "value": "y" }] }]""")
        assertEquals(ActionOutcome.Done(JsonPrimitive(0)), runner.run("demo.task"))
        assertEquals(ResolvedTask.Label("build env1"), host.tasks.single())
        runner.run("demo.snip")
        assertEquals("print(sel)", host.snippets.single().first)
        assertEquals(ActionOutcome.Done(json("""[{"uri":"file:///workspace/a.py"}]""")), runner.run("demo.lsp"))
        assertEquals(Triple("python", "textDocument/definition", null), host.lsp.single())
        assertEquals(ActionOutcome.Done(JsonPrimitive("Open")), runner.run("demo.msg"))
        assertEquals(listOf("bottom"), host.stages)
        host.pickAnswer = { r -> r.items.map { it.value } }
        assertEquals(ActionOutcome.Done(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("B")))), runner.run("demo.pick"))
        settings.set("demo.mode", "\"c\"")
        assertEquals(ActionOutcome.Done(JsonPrimitive("a")), runner.run("demo.toggle"))
        host.messages.clear()
        runner.run("demo.input")
        assertEquals("typed typed x", host.messages.single().text)
        assertEquals(1, host.inputs.size)
        assertEquals("me", host.inputs.single().value)
    }

    @Test fun `L2 commands dispatch to the logic host`() = runTest {
        val d = pack(mapOf("x" to """{ "type": "showMessage", "text": "x" }"""))
        bindings["demo.wasm"] = bindings["demo.x"]!!.copy(commandId = "demo.wasm", handler = CommandHandler.Logic)
        assertEquals(ActionOutcome.Done(JsonPrimitive("wasm:demo.wasm")), runner.run("demo.wasm"))
        assertEquals("acme.demo", d.id.value)
    }

    @Test fun `runStep serves L2 host functions with the same checks`() = runTest {
        pack(mapOf("x" to """{ "type": "showMessage", "text": "x" }"""))
        val ctx = runner.newContext(bindings["demo.x"]!!, null)
        val step = Action.SandboxExec(listOf(Template.literal("ls")), null, emptyMap(), null, ExecOutput.CAPTURE, null)
        assertEquals(ActionError.CAPABILITY, (runner.runStep(ctx, step) as StepResult.Failed).error)
        val ok = runner.runStep(ctx, Action.ShowMessage(Template.literal("hello"), MessageSeverity.INFO, emptyList(), "m"))
        assertEquals(StepResult.Value(JsonNull), ok)
        assertEquals(JsonNull, ctx.results["m"])
        assertTrue(ctx.inputs.isEmpty())
    }
}
