package dev.easyide.lsp.client

import dev.easyide.lsp.jsonrpc.ErrorCodes
import dev.easyide.lsp.jsonrpc.RpcMessage
import dev.easyide.lsp.protocol.CompletionTriggerKind
import dev.easyide.lsp.protocol.FormattingOptions
import dev.easyide.lsp.protocol.LspFeature
import dev.easyide.lsp.protocol.Position
import dev.easyide.lsp.protocol.PrepareRename
import dev.easyide.lsp.protocol.Range
import dev.easyide.lsp.protocol.SignatureHelpContext
import dev.easyide.lsp.session.FeatureFilter
import dev.easyide.lsp.session.LspRequestException
import dev.easyide.lsp.session.SessionState
import dev.easyide.lsp.testing.FakeLanguageServer
import dev.easyide.lsp.testing.LspTestHarness
import dev.easyide.lsp.testing.Reply
import dev.easyide.lsp.testing.serverConfig
import dev.easyide.lsp.testing.testSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LspClientTest {
    private val h = LspTestHarness(testSettings(requestTimeoutMs = 1500))
    private val client = LspClient(h.manager)
    private val uri = "file:///workspace/app.py"
    private val doc = DocContext("e", "p", uri, "python")
    private val pyright get() = h.key("e", "p", "pyright")
    private val ruff get() = h.key("e", "p", "ruff")

    @After
    fun tearDown() = h.close()

    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)

    private fun caps(s: String) = j(s).jsonObject

    /** pyright (everything but formatting) + ruff (`features.only` diagnostics, codeAction, formatting). */
    private suspend fun pythonPack(): Pair<FakeLanguageServer, FakeLanguageServer> {
        h.launcher.configure = { key, _ ->
            capabilities = if (key.serverId == "pyright") PYRIGHT_CAPS else RUFF_CAPS
            if (key.serverId == "pyright") scriptPyright(this) else scriptRuff(this)
        }
        h.setServers(
            "e", "p",
            serverConfig("pyright", memoryBudgetMb = 500, priority = 1),
            serverConfig("ruff", memoryBudgetMb = 80, features = FeatureFilter(setOf(LspFeature.DIAGNOSTICS, LspFeature.CODE_ACTION, LspFeature.FORMATTING), emptySet())),
        )
        client.openDocument(doc, "import os\nx = 1\n")
        h.awaitState(pyright) { it == SessionState.Running }
        h.awaitState(ruff) { it == SessionState.Running }
        return h.launcher.latest(pyright) to h.launcher.latest(ruff)
    }

    private fun scriptPyright(s: FakeLanguageServer) {
        s.onResult("textDocument/hover") { j("""{"contents":{"kind":"markdown","value":"x: int"}}""") }
        s.onResult("textDocument/completion") { j("""{"isIncomplete":false,"items":[{"label":"os","kind":9}]}""") }
        s.onResult("textDocument/definition") { j("""[{"uri":"file:///usr/lib/python3.12/os.py","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":0}}}]""") }
        s.onResult("textDocument/formatting") { j("[]") }
        s.onResult("textDocument/prepareRename") { j("""{"range":{"start":{"line":1,"character":0},"end":{"line":1,"character":1}},"placeholder":"x"}""") }
        s.onResult("textDocument/rename") { j("""{"changes":{"file:///workspace/app.py":[{"range":{"start":{"line":1,"character":0},"end":{"line":1,"character":1}},"newText":"y"}]}}""") }
        s.onResult("textDocument/signatureHelp") { j("""{"signatures":[{"label":"f(a)","parameters":[{"label":"a"}]}]}""") }
        s.onResult("textDocument/codeAction") { j("""[{"title":"Add import","kind":"quickfix"}]""") }
    }

    private fun scriptRuff(s: FakeLanguageServer) {
        s.onResult("textDocument/formatting") { j("""[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":0}},"newText":"# formatted\n"}]""") }
        s.onResult("textDocument/codeAction") { j("""[{"title":"Remove unused import","kind":"quickfix","isPreferred":true},{"title":"Add import","kind":"quickfix"}]""") }
        s.onResult("textDocument/hover") { fail("ruff must not get hover: features.only"); JsonNull }
    }

    @Test
    fun featuresOnlyRoutesFormattingToRuffAndHoverToPyright() = runBlocking {
        pythonPack()
        val formatted = client.formatting(doc, FormattingOptions(4, true), defaultFormatter = null)!!
        assertEquals(ruff, formatted.server)
        assertEquals("# formatted\n", formatted.value.single().newText)
        val hover = client.hover(doc, Position(1, 0))
        assertEquals(listOf(pyright), hover.map { it.server })
        assertTrue(h.manager.session(pyright)!!.supports(LspFeature.HOVER))
        assertTrue(!h.manager.session(ruff)!!.supports(LspFeature.HOVER))
    }

    @Test
    fun mergedCodeActionsAreDedupedAndRememberTheirServer() = runBlocking {
        pythonPack()
        val actions = client.codeActions(doc, Range(Position(0, 0), Position(0, 9)), emptyList(), null, dev.easyide.lsp.protocol.CodeActionTriggerKind.AUTOMATIC)
        assertEquals(listOf("Add import", "Remove unused import"), actions.map { it.value.title })
        assertEquals(pyright, actions.first().server)
    }

    @Test
    fun diagnosticsFromBothServersMergeUnderTheirKeys() = runBlocking {
        val (py, rf) = pythonPack()
        val version = h.manager.documentStore("e", "p").snapshot(uri)!!.version
        py.notifyClient("textDocument/publishDiagnostics", j("""{"uri":"$uri","version":$version,"diagnostics":[{"range":{"start":{"line":1,"character":0},"end":{"line":1,"character":1}},"message":"type","source":"Pyright"}]}"""))
        rf.notifyClient("textDocument/publishDiagnostics", j("""{"uri":"file:/workspace/app.py","diagnostics":[{"range":{"start":{"line":0,"character":7},"end":{"line":0,"character":9}},"message":"unused","source":"Ruff","code":"F401"}]}"""))
        // Diagnostics for a guest file with no host counterpart are dropped.
        rf.notifyClient("textDocument/publishDiagnostics", j("""{"uri":"file:///proc/1/x","diagnostics":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"message":"m"}]}"""))
        val byServer = withTimeout(WAIT_MS) {
            while ((client.diagnostics("e", "p").value[uri]?.size ?: 0) < 2) delay(POLL_MS)
            client.diagnostics("e", "p").value.getValue(uri)
        }
        assertEquals(setOf("Pyright"), byServer.getValue(pyright).items.map { it.source }.toSet())
        assertEquals(version, byServer.getValue(pyright).version)
        assertEquals("F401", byServer.getValue(ruff).items.single().code)
        assertEquals(1, client.diagnostics("e", "p").value.size)
        // An empty publish clears that server only.
        rf.notifyClient("textDocument/publishDiagnostics", j("""{"uri":"$uri","diagnostics":[]}"""))
        withTimeout(WAIT_MS) { while (client.diagnostics("e", "p").value[uri]?.containsKey(ruff) == true) delay(POLL_MS) }
        assertTrue(client.diagnostics("e", "p").value.getValue(uri).containsKey(pyright))
    }

    @Test
    fun requestFlushesTheDocumentFirstAndIsStampedWithItsVersion() = runBlocking {
        val (py, _) = pythonPack()
        client.changeDocument("e", "p", uri, "import os\nx = 12\n")
        val hover = client.hover(doc, Position(1, 0)).single()
        val msgs = py.received.toList()
        val change = msgs.indexOfFirst { it is RpcMessage.Notification && it.method == "textDocument/didChange" }
        val req = msgs.indexOfLast { it is RpcMessage.Request && it.method == "textDocument/hover" }
        assertTrue("didChange $change must precede hover $req", change in 0 until req)
        assertEquals(2, hover.version)
        val sentVersion = (msgs[change] as RpcMessage.Notification).params!!.jsonObject["textDocument"]!!.jsonObject["version"]!!.jsonPrimitive.int
        assertEquals(2, sentVersion)
    }

    @Test
    fun debounceFlushesChangesWithoutARequest() = runBlocking {
        val (py, _) = pythonPack()
        client.changeDocument("e", "p", uri, "a")
        client.changeDocument("e", "p", uri, "ab")
        client.changeDocument("e", "p", uri, "abc")
        val changes = py.awaitNotifications("textDocument/didChange", 1)
        delay(SETTLE_MS)
        // Three edits inside the debounce window coalesce into one didChange at the last version.
        assertEquals(1, py.notifications("textDocument/didChange").size)
        assertEquals(4, changes.single().params!!.jsonObject["textDocument"]!!.jsonObject["version"]!!.jsonPrimitive.int)
    }

    @Test
    fun timeoutIsSilentAndCancelsTheRequest() = runBlocking {
        val (py, _) = pythonPack()
        py.onRequest("textDocument/hover") { Reply.Never }
        assertTrue(client.hover(doc, Position(1, 0)).isEmpty())
        py.awaitMessage { it is RpcMessage.Notification && it.method == "\$/cancelRequest" }
        assertTrue(h.manager.session(pyright)!!.log.snapshot().any { it.startsWith("timeout textDocument/hover") })
        assertTrue(h.ui.shown.isEmpty())
    }

    @Test
    fun supersededRequestIsCancelled() = runBlocking {
        val (py, _) = pythonPack()
        val started = CompletableDeferred<Unit>()
        py.onRequest("textDocument/completion") { started.complete(Unit); Reply.Never }
        val job = launch { client.completion(doc, Position(1, 1), CompletionTriggerKind.INVOKED, null) }
        withTimeout(WAIT_MS) { started.await() }
        job.cancelAndJoin()
        py.awaitMessage { it is RpcMessage.Notification && it.method == "\$/cancelRequest" }
        Unit
    }

    @Test
    fun contentModifiedAndCancelledAreSilentButOtherErrorsReachSingleOwnerCallers() = runBlocking {
        val (py, rf) = pythonPack()
        py.onRequest("textDocument/hover") { Reply.Error(ErrorCodes.CONTENT_MODIFIED, "modified") }
        assertTrue(client.hover(doc, Position(0, 0)).isEmpty())
        rf.onRequest("textDocument/formatting") { Reply.Error(ErrorCodes.REQUEST_FAILED, "ruff: syntax error") }
        try {
            client.formatting(doc, FormattingOptions(4, true), null)
            fail("formatting error must reach the caller")
        } catch (e: LspRequestException) {
            assertEquals("ruff: syntax error", e.message)
        }
    }

    @Test
    fun defaultFormatterPicksTheOwnerWhenSeveralCanFormat() = runBlocking {
        h.launcher.configure = { key, _ ->
            capabilities = caps("""{"textDocumentSync":2,"documentFormattingProvider":true}""")
            onResult("textDocument/formatting") { j("""[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":0}},"newText":"${key.serverId}"}]""") }
        }
        h.setServers("e", "p", serverConfig("black", priority = 5), serverConfig("yapf"))
        client.openDocument(doc, "x")
        h.awaitState(h.key("e", "p", "black")) { it == SessionState.Running }
        h.awaitState(h.key("e", "p", "yapf")) { it == SessionState.Running }
        assertEquals("black", client.formatting(doc, FormattingOptions(4, true), null)!!.value.single().newText)
        assertEquals("yapf", client.formatting(doc, FormattingOptions(4, true), "yapf")!!.value.single().newText)
    }

    @Test
    fun completionTriggerKindIsPerServerTriggerCharacters() = runBlocking {
        h.launcher.configure = { key, _ ->
            capabilities = if (key.serverId == "a") caps("""{"textDocumentSync":2,"completionProvider":{"triggerCharacters":["."]}}""")
            else caps("""{"textDocumentSync":2,"completionProvider":{"triggerCharacters":[":"]}}""")
            onResult("textDocument/completion") { j("""[{"label":"${key.serverId}"}]""") }
        }
        h.setServers("e", "p", serverConfig("a"), serverConfig("b"))
        client.openDocument(doc, "x.")
        h.awaitState(h.key("e", "p", "a")) { it == SessionState.Running }
        h.awaitState(h.key("e", "p", "b")) { it == SessionState.Running }
        val lists = client.completion(doc, Position(0, 2), CompletionTriggerKind.TRIGGER_CHARACTER, ".")
        assertEquals(setOf("a", "b"), lists.flatMap { r -> r.value.items.map { it.label } }.toSet())
        fun kindSent(id: String) = h.launcher.latest(h.key("e", "p", id)).requests("textDocument/completion").single()
            .params!!.jsonObject["context"]!!.jsonObject["triggerKind"]!!.jsonPrimitive.int
        assertEquals(2, kindSent("a"))
        assertEquals(1, kindSent("b"))
    }

    @Test
    fun navigationRenameAndSignatureHelp() = runBlocking {
        pythonPack()
        val def = client.definition(doc, Position(0, 7))!!
        assertEquals("file:///usr/lib/python3.12/os.py", def.value.single().uri)
        assertTrue(h.manager.pathMapper("e", "p").toHost(def.value.single().uri)!!.readOnly)
        assertEquals("x", (client.prepareRename(doc, Position(1, 0))!!.value as PrepareRename.At).placeholder)
        val edit = client.rename(doc, Position(1, 0), "y")!!
        val applied = client.applyEdit("e", "p", edit.value, "Rename")
        assertTrue(applied.applied)
        assertEquals("y", h.edits.textEdits.single().second.single().newText)
        val sig = client.signatureHelp(doc, Position(1, 0), SignatureHelpContext(1, null, false, null))!!
        assertEquals("f(a)", sig.value.active!!.label)
        assertNull(client.typeDefinition(doc, Position(0, 0)))
    }

    @Test
    fun serverRequestsAreAnsweredThroughPorts() = runBlocking {
        h.configuration.values["python.analysis"] = j("""{"typeCheckingMode":"strict"}""")
        h.configuration.sections.value = mapOf("python" to j("""{"analysis":{"typeCheckingMode":"strict"}}"""))
        h.launcher.configure = { _, _ -> capabilities = PYRIGHT_CAPS }
        h.setServers("e", "p", serverConfig("pyright", settingsSection = "python"))
        client.openDocument(doc, "x")
        h.awaitState(pyright) { it == SessionState.Running }
        val py = h.launcher.latest(pyright)
        val cfg = py.requestClient("workspace/configuration", j("""{"items":[{"scopeUri":"$uri","section":"python.analysis"},{"section":"unknown"}]}"""))
        assertEquals(j("""[{"typeCheckingMode":"strict"},null]"""), cfg.result)
        val didChange = py.awaitNotifications("workspace/didChangeConfiguration", 1).single()
        assertEquals(j("""{"analysis":{"typeCheckingMode":"strict"}}"""), didChange.params!!.jsonObject["settings"])
        h.configuration.sections.value = mapOf("python" to j("""{"analysis":{"typeCheckingMode":"basic"}}"""))
        py.awaitNotifications("workspace/didChangeConfiguration", 2)
        val folders = py.requestClient("workspace/workspaceFolders", null)
        assertEquals("file:///workspace", folders.result!!.jsonArray.single().jsonObject["uri"]!!.jsonPrimitive.content)
        val apply = py.requestClient("workspace/applyEdit", j("""{"label":"Fix","edit":{"changes":{"$uri":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"newText":"y"}]}}}"""))
        assertEquals(JsonPrimitive(true), apply.result!!.jsonObject["applied"])
        val refused = py.requestClient("workspace/applyEdit", j("""{"edit":{"changes":{"file:///usr/lib/x.py":[]}}}"""))
        assertEquals(JsonPrimitive(false), refused.result!!.jsonObject["applied"])
        assertEquals(JsonNull, py.requestClient("window/workDoneProgress/create", j("""{"token":"t1"}""")).result)
        py.notifyClient("\$/progress", j("""{"token":"t1","value":{"kind":"begin","title":"Indexing","percentage":10}}"""))
        val session = h.manager.session(pyright)!!
        withTimeout(WAIT_MS) { while (session.progress.value["t1"]?.title != "Indexing") delay(POLL_MS) }
        py.notifyClient("\$/progress", j("""{"token":"t1","value":{"kind":"end"}}"""))
        withTimeout(WAIT_MS) { while (session.progress.value.isNotEmpty()) delay(POLL_MS) }
        py.requestClient("client/registerCapability", j("""{"registrations":[{"id":"w1","method":"workspace/didChangeWatchedFiles","registerOptions":{"watchers":[{"globPattern":"**/*.py"}]}}]}"""))
        assertEquals("workspace/didChangeWatchedFiles", session.registrations.value.getValue("w1").method)
        py.requestClient("client/unregisterCapability", j("""{"unregisterations":[{"id":"w1","method":"workspace/didChangeWatchedFiles"}]}"""))
        assertTrue(session.registrations.value.isEmpty())
        h.ui.answer = "Install"
        val asked = py.requestClient("window/showMessageRequest", j("""{"type":2,"message":"Install stubs?","actions":[{"title":"Install"},{"title":"Later"}]}"""))
        assertEquals("Install", asked.result!!.jsonObject["title"]!!.jsonPrimitive.content)
        py.notifyClient("window/showMessage", j("""{"type":1,"message":"Pyright crashed a bit"}"""))
        withTimeout(WAIT_MS) { while (h.ui.shown.isEmpty()) delay(POLL_MS) }
        assertEquals(dev.easyide.lsp.jsonrpc.ErrorCodes.METHOD_NOT_FOUND, py.requestClient("window/showDocument", j("{}")).error!!.code)
    }

    @Test
    fun pullDiagnosticsReplacePushForServersThatOfferThem() = runBlocking {
        h.launcher.configure = { _, _ ->
            capabilities = caps("""{"textDocumentSync":2,"diagnosticProvider":{"identifier":"basedpyright","interFileDependencies":true,"workspaceDiagnostics":false}}""")
            var calls = 0
            onRequest("textDocument/diagnostic") { params ->
                calls++
                val prev = params!!.jsonObject["previousResultId"]
                if (prev != null) Reply.Result(j("""{"kind":"unchanged","resultId":"r$calls"}"""))
                else Reply.Result(j("""{"kind":"full","resultId":"r$calls","items":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"message":"pulled"}]}"""))
            }
        }
        h.setServers("e", "p", serverConfig("pyright"))
        client.openDocument(doc, "x")
        h.awaitState(pyright) { it == SessionState.Running }
        val py = h.launcher.latest(pyright)
        withTimeout(WAIT_MS) { while (client.diagnostics("e", "p").value[uri] == null) delay(POLL_MS) }
        assertEquals("basedpyright", py.requests("textDocument/diagnostic").first().params!!.jsonObject["identifier"]!!.jsonPrimitive.content)
        py.notifyClient("textDocument/publishDiagnostics", j("""{"uri":"$uri","diagnostics":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"message":"pushed"}]}"""))
        client.changeDocument("e", "p", uri, "xy")
        withTimeout(WAIT_MS) { while (py.requests("textDocument/diagnostic").size < 2) delay(POLL_MS) }
        delay(SETTLE_MS)
        val items = client.diagnostics("e", "p").value.getValue(uri).getValue(pyright).items
        assertEquals(listOf("pulled"), items.map { it.message })
        assertEquals("r1", py.requests("textDocument/diagnostic")[1].params!!.jsonObject["previousResultId"]!!.jsonPrimitive.content)
        // A server refresh re-pulls every open document.
        py.requestClient("workspace/diagnostic/refresh", null)
        withTimeout(WAIT_MS) { while (py.requests("textDocument/diagnostic").size < 3) delay(POLL_MS) }
        Unit
    }

    @Test
    fun semanticTokensFullThenDelta() = runBlocking {
        h.launcher.configure = { _, _ ->
            capabilities = caps("""{"textDocumentSync":2,"semanticTokensProvider":{"legend":{"tokenTypes":["class","function"],"tokenModifiers":["declaration"]},"full":{"delta":true}}}""")
            onResult("textDocument/semanticTokens/full") { j("""{"resultId":"1","data":[0,0,5,0,1,1,4,3,1,0]}""") }
            onResult("textDocument/semanticTokens/full/delta") { j("""{"resultId":"2","edits":[{"start":5,"deleteCount":5,"data":[1,2,3,1,0]}]}""") }
        }
        h.setServers("e", "p", serverConfig("gopls", languages = setOf("python")))
        client.openDocument(doc, "class A:\n    def f(self): pass\n")
        h.awaitState(h.key("e", "p", "gopls")) { it == SessionState.Running }
        val full = client.semanticTokens(doc)!!.value.tokens
        assertEquals(listOf("class", "function"), full.map { it.type })
        assertEquals(setOf("declaration"), full[0].modifiers)
        val delta = client.semanticTokens(doc)!!.value.tokens
        assertEquals(2, delta[1].start)
        assertEquals(3, delta[1].length)
        val server = h.launcher.latest(h.key("e", "p", "gopls"))
        assertEquals("1", server.requests("textDocument/semanticTokens/full/delta").single().params!!.jsonObject["previousResultId"]!!.jsonPrimitive.content)
    }

    @Test
    fun resolveGoesBackToTheOwningServer() = runBlocking {
        h.launcher.configure = { key, _ ->
            capabilities = caps("""{"textDocumentSync":2,"completionProvider":{"resolveProvider":true}}""")
            onResult("textDocument/completion") { j("""[{"label":"${key.serverId}","data":{"id":7}}]""") }
            onResult("completionItem/resolve") { p -> j("""{"label":"${key.serverId}","documentation":"from ${key.serverId} ${p!!.jsonObject["data"]!!.jsonObject["id"]}"}""") }
        }
        h.setServers("e", "p", serverConfig("a"), serverConfig("b"))
        client.openDocument(doc, "x")
        h.awaitState(h.key("e", "p", "a")) { it == SessionState.Running }
        h.awaitState(h.key("e", "p", "b")) { it == SessionState.Running }
        val lists = client.completion(doc, Position(0, 1), CompletionTriggerKind.INVOKED, null)
        val fromB = lists.singleOrNull { it.server.serverId == "b" }
            ?: error("no b: ${lists.map { it.server }} log=${h.manager.session(h.key("e", "p", "b"))!!.log.snapshot()}")
        val item = FromServer(fromB.server, fromB.value.items.single(), fromB.version)
        assertEquals("from b 7", client.resolveCompletion("e", "p", item).documentation!!.value)
    }

    private companion object {
        const val WAIT_MS = 8000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 300L
        val PYRIGHT_CAPS = Json.parseToJsonElement("""
            {"textDocumentSync":{"openClose":true,"change":2,"save":{"includeText":false}},"hoverProvider":true,"definitionProvider":true,
             "completionProvider":{"triggerCharacters":["."]},"signatureHelpProvider":{"triggerCharacters":["("]},
             "renameProvider":{"prepareProvider":true},"codeActionProvider":true}
        """).jsonObject
        val RUFF_CAPS = Json.parseToJsonElement("""
            {"textDocumentSync":{"openClose":true,"change":2},"hoverProvider":true,"codeActionProvider":{"codeActionKinds":["quickfix"]},"documentFormattingProvider":true}
        """).jsonObject
    }
}
