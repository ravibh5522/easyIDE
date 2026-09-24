package dev.easyide.extwasm

import dev.easyide.extwasm.host.Cap
import dev.easyide.extwasm.host.CapabilitySet
import dev.easyide.extwasm.host.Dispatch
import dev.easyide.extwasm.host.HostCallRouter
import dev.easyide.extwasm.host.HostFunctionTable
import dev.easyide.extwasm.host.InstanceSession
import dev.easyide.extwasm.host.LogLevel
import dev.easyide.extwasm.host.ProviderRegistration
import dev.easyide.extwasm.testing.EMPTY
import dev.easyide.extwasm.testing.FakePorts
import dev.easyide.extwasm.testing.MapSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** The R-SEC-09 matrix: every host function with and without its capability. */
class HostCallRouterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fake = FakePorts()
    private val router = HostCallRouter(fake.ports)
    private val providers = mutableListOf<ProviderRegistration>()
    private val delivered = mutableListOf<Pair<String, JsonObject>>()

    private fun session(caps: List<String>, limits: WasmLimits = WasmLimits.resolve(MapSettings())) = InstanceSession(
        Fixtures.extension(tmp.root, caps = caps), limits,
        deliver = { e, d -> delivered += e to d },
        registerProvider = { providers += it },
    )

    private fun call(caps: List<String>, fn: String, args: JsonObject): Dispatch =
        runBlocking { router.dispatch(session(caps), fn, args) }

    private fun obj(vararg kv: Pair<String, Any>) = buildJsonObject {
        for ((k, v) in kv) when (v) {
            is String -> put(k, v)
            is Number -> put(k, v)
            is JsonArray -> put(k, v)
            is JsonObject -> put(k, v)
            else -> error("unsupported $v")
        }
    }

    private fun strings(vararg s: String) = JsonArray(s.map(::JsonPrimitive))

    /** fn -> (args, capabilities that make it allowed). Empty caps = always available. */
    private val matrix: Map<String, Pair<JsonObject, List<String>>> = mapOf(
        "host.functions" to (EMPTY to emptyList()),
        "host.info" to (EMPTY to emptyList()),
        "log.write" to (obj("level" to "info", "message" to "hi") to emptyList()),
        "editor.active" to (EMPTY to listOf(Cap.FS_READ)),
        "editor.getText" to (EMPTY to listOf(Cap.FS_READ)),
        "editor.applyEdits" to (obj("edits" to JsonArray(emptyList())) to listOf(Cap.FS_WRITE)),
        "editor.setSelections" to (EMPTY to listOf(Cap.FS_WRITE)),
        "editor.insertSnippet" to (obj("snippet" to "x") to emptyList()),
        "editor.decorate" to (obj("kind" to "diagnostic", "items" to JsonArray(emptyList())) to listOf(Cap.FS_WRITE)),
        "fs.read" to (obj("path" to "/workspace/a") to listOf(Cap.FS_READ)),
        "fs.stat" to (obj("path" to "/workspace/a") to listOf(Cap.FS_READ)),
        "fs.list" to (obj("path" to "/workspace") to listOf(Cap.FS_READ)),
        "fs.watch" to (obj("glob" to "**/*.py") to listOf(Cap.FS_READ)),
        "fs.write" to (obj("path" to "/workspace/a", "content" to "x") to listOf(Cap.FS_WRITE)),
        "fs.delete" to (obj("path" to "/workspace/a") to listOf(Cap.FS_WRITE)),
        "fs.rename" to (obj("from" to "/workspace/a", "to" to "/workspace/b") to listOf(Cap.FS_WRITE)),
        "events.subscribe" to (obj("names" to strings("workspace.didSave")) to emptyList()),
        "config.get" to (obj("key" to "editor.fontSize") to listOf(Cap.UI_SETTINGS)),
        "config.set" to (obj("key" to "editor.fontSize", "value" to 12) to listOf(Cap.UI_SETTINGS)),
        "ui.showMessage" to (obj("text" to "hi") to emptyList()),
        "ui.showQuickPick" to (obj("items" to JsonArray(emptyList())) to emptyList()),
        "ui.showInputBox" to (EMPTY to emptyList()),
        "ui.setStatusBarItem" to (obj("id" to "s") to emptyList()),
        "ui.setViewData" to (obj("viewId" to "acme.stage", "items" to JsonArray(emptyList())) to listOf(Cap.UI_STAGE)),
        "ui.revealStage" to (EMPTY to emptyList()),
        "commands.execute" to (obj("command" to "git.push") to listOf(Cap.SANDBOX_EXEC)),
        "commands.register" to (obj("command" to "test.proxy") to emptyList()),
        "providers.register" to (obj("kind" to "completion", "languages" to strings("python")) to emptyList()),
        "lsp.request" to (obj("language" to "python", "method" to "textDocument/hover") to listOf(Cap.LSP_REQUEST)),
        "lsp.notify" to (obj("language" to "python", "method" to "x") to listOf(Cap.LSP_REQUEST)),
        "lsp.status" to (obj("language" to "python") to listOf(Cap.LSP_REQUEST)),
        "sandbox.exec" to (obj("argv" to strings("ls")) to listOf(Cap.SANDBOX_EXEC)),
        "sandbox.kill" to (obj("handle" to 1) to listOf(Cap.SANDBOX_EXEC)),
        "clipboard.read" to (EMPTY to listOf(Cap.CLIPBOARD)),
        "clipboard.write" to (obj("text" to "x") to listOf(Cap.CLIPBOARD)),
        "net.fetch" to (obj("url" to "https://api.example.com/x") to listOf("network(*.example.com)")),
        "storage.get" to (obj("key" to "k") to emptyList()),
        "storage.set" to (obj("key" to "k", "value" to 1) to emptyList()),
        "storage.delete" to (obj("key" to "k") to emptyList()),
        "storage.keys" to (EMPTY to emptyList()),
        "secrets.get" to (obj("name" to "token") to listOf(Cap.SECRETS_READ)),
    )

    @Test fun matrixCoversEveryTableRow() {
        assertEquals(HostFunctionTable.rows.keys, matrix.keys)
    }

    @Test fun everyFunctionIsAllowedWithItsCapabilityAndDeniedWithout() {
        fake.commandCapability = mapOf("git.push" to Cap.SANDBOX_EXEC)
        for ((fn, spec) in matrix) {
            val (args, caps) = spec
            val allowed = call(caps, fn, args)
            assertTrue("$fn with $caps: $allowed", allowed is Dispatch.Ok)
            if (caps.isNotEmpty()) {
                val denied = call(emptyList(), fn, args)
                assertEquals("$fn without $caps", ErrorCode.E_CAPABILITY, (denied as Dispatch.Err).code)
            }
        }
    }

    @Test fun writeImpliesReadButNotTheReverse() {
        assertTrue(call(listOf(Cap.FS_WRITE), "fs.read", obj("path" to "/workspace/a")) is Dispatch.Ok)
        val w = call(listOf(Cap.FS_READ), "fs.write", obj("path" to "/workspace/a"))
        assertEquals(ErrorCode.E_CAPABILITY, (w as Dispatch.Err).code)
    }

    @Test fun pathsOutsideTheProjectNeedOutsideProjectAfterNormalisation() {
        for (p in listOf("/workspace/../etc/passwd", "/etc/passwd", "/workspace2/x")) {
            val r = call(listOf(Cap.FS_WRITE), "fs.write", obj("path" to p))
            assertEquals(p, ErrorCode.E_CAPABILITY, (r as Dispatch.Err).code)
        }
        assertTrue(call(listOf(Cap.FS_WRITE, Cap.FS_OUTSIDE), "fs.write", obj("path" to "/etc/x")) is Dispatch.Ok)
        assertEquals(listOf("/etc/x"), fake.writes)
        val rel = call(listOf(Cap.FS_READ), "fs.read", obj("path" to "a.txt"))
        assertEquals(ErrorCode.E_ARGS, (rel as Dispatch.Err).code)
        assertTrue(call(listOf(Cap.FS_READ), "fs.read", obj("path" to "/workspace/./x/../a")) is Dispatch.Ok)
    }

    @Test fun renameChecksBothEnds() {
        val r = call(listOf(Cap.FS_WRITE), "fs.rename", obj("from" to "/workspace/a", "to" to "/tmp/a"))
        assertEquals(ErrorCode.E_CAPABILITY, (r as Dispatch.Err).code)
    }

    @Test fun absoluteWatchGlobOutsideTheProjectNeedsOutsideProject() {
        assertEquals(ErrorCode.E_CAPABILITY, (call(listOf(Cap.FS_READ), "fs.watch", obj("glob" to "/etc/**")) as Dispatch.Err).code)
        assertTrue(call(listOf(Cap.FS_READ), "fs.watch", obj("glob" to "/workspace/src/**")) is Dispatch.Ok)
    }

    @Test fun ownSettingsNeedNothingProtectedKeysAreNeverWritable() {
        assertTrue(call(emptyList(), "config.set", obj("key" to "acme.enabled", "value" to 1)) is Dispatch.Ok)
        for (k in listOf("extensions.wasm.fuelPerCall", "lsp.servers", "profiles.active", "keybindings")) {
            val r = call(listOf(Cap.UI_SETTINGS), "config.set", obj("key" to k, "value" to 1))
            assertEquals(k, ErrorCode.E_CAPABILITY, (r as Dispatch.Err).code)
        }
        assertEquals(listOf("acme.enabled"), fake.configSets)
    }

    @Test fun networkRequiresHttpsAndADeclaredHost() {
        val caps = listOf("network(api.example.com, *.cdn.net)")
        assertTrue(call(caps, "net.fetch", obj("url" to "https://API.example.com/v1")) is Dispatch.Ok)
        assertTrue(call(caps, "net.fetch", obj("url" to "https://a.b.cdn.net/")) is Dispatch.Ok)
        for (u in listOf("http://api.example.com/", "https://evil.com/", "https://cdn.net/", "https://api.example.com.evil.com/")) {
            assertEquals(u, ErrorCode.E_CAPABILITY, (call(caps, "net.fetch", obj("url" to u)) as Dispatch.Err).code)
        }
        val req = fake.fetches.first()
        assertEquals(WasmLimits.resolve(MapSettings()).netMaxResponseBytes, req.maxResponseBytes)
        assertTrue(req.hostAllowed("x.cdn.net"))
        assertFalse(req.hostAllowed("evil.com"))
    }

    @Test fun sandboxExecNeverPassesReservedEnvironment() {
        val r = call(listOf(Cap.SANDBOX_EXEC), "sandbox.exec", obj(
            "argv" to strings("git", "push"),
            "env" to obj("EASYIDE_GIT_TOKEN" to "t", "ANTHROPIC_API_KEY" to "k", "PATH" to "/bin"),
            "output" to "terminal",
        ))
        assertTrue(r is Dispatch.Ok)
        assertEquals(mapOf("PATH" to "/bin"), fake.execs.single().env)
        assertEquals(listOf("git", "push"), fake.execs.single().argv)
    }

    @Test fun pendingHandlesAreCappedAndFreedOnFinish() {
        val s = session(listOf(Cap.SANDBOX_EXEC))
        val exec = obj("argv" to strings("ls"))
        repeat(WasmPolicy.MAX_PENDING_HANDLES) { assertTrue(runBlocking { router.dispatch(s, "sandbox.exec", exec) } is Dispatch.Ok) }
        assertEquals(ErrorCode.E_LIMIT, (runBlocking { router.dispatch(s, "sandbox.exec", exec) } as Dispatch.Err).code)
        fake.sinks.first().finish("sandbox.exit", obj("code" to 0))
        fake.sinks.first().finish("sandbox.exit", obj("code" to 0))
        assertEquals(1, delivered.size)
        assertTrue(runBlocking { router.dispatch(s, "sandbox.exec", exec) } is Dispatch.Ok)
    }

    @Test fun storageQuotaIsEnforcedOnSet() {
        val limits = WasmLimits.resolve(MapSettings(mapOf(WasmSetting.STORAGE_QUOTA_KB.key to 1)))
        val s = session(emptyList(), limits)
        val big = "x".repeat(600)
        assertTrue(runBlocking { router.dispatch(s, "storage.set", obj("key" to "a", "value" to big)) } is Dispatch.Ok)
        // Overwriting the same key counts only the new value.
        assertTrue(runBlocking { router.dispatch(s, "storage.set", obj("key" to "a", "value" to big)) } is Dispatch.Ok)
        val over = runBlocking { router.dispatch(s, "storage.set", obj("key" to "b", "value" to big)) }
        assertEquals(ErrorCode.E_LIMIT, (over as Dispatch.Err).code)
    }

    @Test fun declaredSetsBoundRegistrations() {
        assertEquals(ErrorCode.E_CAPABILITY, (call(emptyList(), "commands.register", obj("command" to "other.cmd")) as Dispatch.Err).code)
        assertEquals(ErrorCode.E_ARGS, (call(emptyList(), "providers.register", obj("kind" to "codeLens")) as Dispatch.Err).code)
        assertEquals(ErrorCode.E_CAPABILITY, (call(emptyList(), "ui.setViewData", obj("viewId" to "foreign.view")) as Dispatch.Err).code)
        assertTrue(call(emptyList(), "ui.setViewData", obj("viewId" to "acme.view")) is Dispatch.Ok)
        assertTrue(call(emptyList(), "providers.register", obj("kind" to "completion", "languages" to strings("go"))) is Dispatch.Ok)
        assertEquals(listOf(ProviderRegistration("acme.test", "completion", listOf("go"))), providers)
    }

    @Test fun badArgumentsAreEArgsAndPortFailuresAreMapped() {
        assertEquals(ErrorCode.E_ARGS, (call(listOf(Cap.FS_READ), "fs.read", EMPTY) as Dispatch.Err).code)
        assertEquals(ErrorCode.E_ARGS, (call(emptyList(), "log.write", obj("level" to "loud", "message" to "x")) as Dispatch.Err).code)
        assertEquals(ErrorCode.E_ARGS, (call(emptyList(), "events.subscribe", obj("names" to strings("bogus"))) as Dispatch.Err).code)
        fake.failRead = HostCallException(ErrorCode.E_UNAVAILABLE, "environment stopped")
        assertEquals(ErrorCode.E_UNAVAILABLE, (call(listOf(Cap.FS_READ), "fs.read", obj("path" to "/workspace/a")) as Dispatch.Err).code)
        fake.failRead = IOException("disk")
        assertEquals(ErrorCode.E_INTERNAL, (call(listOf(Cap.FS_READ), "fs.read", obj("path" to "/workspace/a")) as Dispatch.Err).code)
        assertTrue(fake.logs.any { it.second == LogLevel.ERROR && it.third.contains("IOException") })
    }

    @Test fun mutatingCallsAreAuditedWithoutContent() {
        call(listOf(Cap.FS_WRITE), "fs.write", obj("path" to "/workspace/a", "content" to "SECRET TEXT"))
        val line = fake.logs.single { it.second == LogLevel.INFO }.third
        assertEquals("fs.write path=/workspace/a", line)
    }

    @Test fun hostFunctionsListsTheTable() {
        val r = call(emptyList(), "host.functions", EMPTY) as Dispatch.Ok
        assertEquals(HostFunctionTable.rows.keys.sorted(), r.result!!.jsonArray.map { (it as JsonPrimitive).content })
    }

    @Test fun capabilitySetFoldsNetworkHostsForTheGuest() {
        val c = CapabilitySet(listOf("fs.project(read)", "network(a.com)", "network(*.b.com)"))
        assertEquals(listOf("fs.project(read)", "network(a.com,*.b.com)"), c.ids)
    }
}
