package dev.easyide.app.extensions.wasm

import dev.easyide.app.extensions.host.ActiveDocument
import dev.easyide.extensions.FakeSettings
import dev.easyide.extensions.settings.ConfigTarget
import dev.easyide.extensions.settings.RuntimeScope
import dev.easyide.extensions.settings.SettingsQuery
import dev.easyide.extwasm.ErrorCode
import dev.easyide.extwasm.HostCallException
import dev.easyide.extwasm.host.StorageScope
import dev.easyide.extwasm.testing.FakePorts
import dev.easyide.extwasm.testing.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** The app-side host ports: file mapping, storage, config rules, editor and clipboard. */
class WasmPortsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private fun code(block: suspend () -> Unit): ErrorCode? = runBlocking {
        try {
            block()
            null
        } catch (e: HostCallException) {
            e.code
        }
    }

    // ---- files

    private fun files(project: File, rootfs: File? = null) =
        WasmFilePort({ GuestRoots(project, rootfs) }, { 1024 }, Dispatchers.IO)

    @Test fun `files map guest paths into the project and back out through list and stat`() = runBlocking {
        val project = tmp.newFolder("project")
        val port = files(project)
        port.write("acme.x", "/workspace/src/a.txt", buildJsonObject { put("text", "héllo") })
        assertEquals("héllo", File(project, "src/a.txt").readText())
        assertEquals(JsonPrimitive("héllo"), port.read("/workspace/src/a.txt", JsonObject(emptyMap())))
        assertEquals("file", port.stat("/workspace/src/a.txt")!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(port.stat("/workspace/missing"))
        assertEquals("src", port.list("/workspace").jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        port.rename("acme.x", "/workspace/src/a.txt", "/workspace/b.txt")
        assertTrue(File(project, "b.txt").isFile)
    }

    @Test fun `a symlink leaving the project is refused with E_CAPABILITY`() {
        val project = tmp.newFolder("project")
        val outside = tmp.newFolder("outside").also { File(it, "secret").writeText("s3cret") }
        Files.createSymbolicLink(File(project, "link").toPath(), outside.toPath())
        val port = files(project)
        assertEquals(ErrorCode.E_CAPABILITY, code { port.read("/workspace/link/secret", JsonObject(emptyMap())) })
        assertEquals(ErrorCode.E_CAPABILITY, code { port.write("acme.x", "/workspace/link/new", buildJsonObject { put("text", "x") }) })
        assertEquals(ErrorCode.E_CAPABILITY, code { port.delete("acme.x", "/workspace/link") })
        assertTrue(File(outside, "secret").isFile)
    }

    @Test fun `paths outside the workspace map to the rootfs and cannot climb out of it`() = runBlocking {
        val project = tmp.newFolder("project")
        val rootfs = tmp.newFolder("rootfs").also { File(it, "etc").mkdirs(); File(it, "etc/os-release").writeText("ID=debian") }
        Files.createSymbolicLink(File(rootfs, "escape").toPath(), tmp.root.toPath())
        val port = files(project, rootfs)
        assertEquals(JsonPrimitive("ID=debian"), port.read("/etc/os-release", JsonObject(emptyMap())))
        assertEquals(ErrorCode.E_CAPABILITY, code { port.read("/escape/project/x", JsonObject(emptyMap())) })
        assertEquals(ErrorCode.E_UNAVAILABLE, code { files(project).read("/etc/os-release", JsonObject(emptyMap())) })
        val none = WasmFilePort({ null }, { 1024 }, Dispatchers.IO)
        assertEquals(ErrorCode.E_UNAVAILABLE, code { none.stat("/workspace/a") })
    }

    @Test fun `reads over the size limit fail E_LIMIT`() {
        val project = tmp.newFolder("project").also { File(it, "big").writeText("x".repeat(2048)) }
        assertEquals(ErrorCode.E_LIMIT, code { files(project).read("/workspace/big", JsonObject(emptyMap())) })
    }

    @Test fun `fs watch globs match saved paths`() = runBlocking {
        val port = files(tmp.newFolder())
        port.watch("acme.a", "**/*.py")
        port.watch("acme.b", "/workspace/docs/*")
        assertEquals(listOf("acme.a"), port.watchersOf("/workspace/src/app.py"))
        assertEquals(listOf("acme.a"), port.watchersOf("/workspace/app.py"))
        assertEquals(listOf("acme.b"), port.watchersOf("/workspace/docs/readme.md"))
        port.unwatchAll("acme.a")
        assertEquals(emptyList<String>(), port.watchersOf("/workspace/app.py"))
    }

    // ---- storage

    @Test fun `storage keeps a JSON file per extension and scope and counts every scope`() = runBlocking {
        var rs = RuntimeScope("env1", "p1")
        val root = tmp.newFolder("storage")
        val port = WasmStoragePort(root, { rs }, Dispatchers.IO)
        port.set("acme.a", StorageScope.GLOBAL, "k", JsonPrimitive("v"))
        port.set("acme.a", StorageScope.PROJECT, "n", JsonPrimitive(42))
        port.set("acme.b", StorageScope.GLOBAL, "k", JsonPrimitive("other"))
        assertEquals(JsonPrimitive("v"), port.get("acme.a", StorageScope.GLOBAL, "k"))
        assertEquals(listOf("n"), port.keys("acme.a", StorageScope.PROJECT))
        assertTrue(File(root, "acme.a/project-p1.json").isFile)
        assertEquals((1 + 3 + 1 + 2).toLong(), port.usedBytes("acme.a"))
        rs = RuntimeScope(null, null)
        assertEquals(ErrorCode.E_UNAVAILABLE, code { port.get("acme.a", StorageScope.ENVIRONMENT, "k") })
        // Other projects' files still count against the extension's quota.
        assertEquals(7L, port.usedBytes("acme.a"))
        port.delete("acme.a", StorageScope.GLOBAL, "k")
        assertNull(port.get("acme.a", StorageScope.GLOBAL, "k"))
    }

    @Test fun `the host refuses a storage write over the quota the port reports`() {
        val fake = FakePorts()
        val storage = WasmStoragePort(tmp.newFolder("storage"), { RuntimeScope.NONE }, Dispatchers.IO)
        val harness = ProxyHarness(tmp.newFolder(), fake.with(storage = storage), MapSettings(mapOf("extensions.storage.quotaKb" to 2)), scope)
        val ext = harness.extension(tmp.newFolder(), emptyList())
        harness.activate(ext)
        // The proxy guest stores its activation message; that already counts.
        val base = runBlocking { storage.usedBytes(ext.id) }
        assertTrue(base > 0)
        fun value(n: Int) = buildJsonObject { put("value", "x".repeat(n)) }["value"]!!
        val small = harness.call(ext, "storage.set", buildJsonObject { put("key", "a"); put("value", value(100)) })
        assertEquals(null, small.errorCode())
        // Fits on its own, not beside "a": the quota spans every key of the extension.
        val fitsAlone = (QUOTA - base - SPARE).toInt() - 1 - 2
        val big = harness.call(ext, "storage.set", buildJsonObject { put("key", "b"); put("value", value(fitsAlone)) })
        assertEquals("E_LIMIT", big.errorCode())
        // Replacing "a" with the same size only counts the difference.
        val replace = harness.call(ext, "storage.set", buildJsonObject { put("key", "a"); put("value", value(fitsAlone)) })
        assertEquals(null, replace.errorCode())
        assertTrue(runBlocking { storage.usedBytes(ext.id) } <= QUOTA)
        assertEquals(listOf("a", "activation"), runBlocking { storage.keys(ext.id, StorageScope.GLOBAL) })
        harness.host.close()
    }

    // ---- config

    @Test fun `config set keeps the L1 rules - own keys free, foreign keys need ui settings, protected keys never`() {
        val settings = FakeSettings()
        val config = WasmConfigPort(settings) { SettingsQuery("python", "env1", "p1") }
        val fake = FakePorts()
        val harness = ProxyHarness(tmp.newFolder(), fake.with(config = config), MapSettings(), scope)
        val plain = harness.extension(tmp.newFolder(), emptyList())
        harness.activate(plain)
        val own = harness.call(plain, "config.set", buildJsonObject { put("key", "acme.enabled"); put("value", false) })
        assertEquals(null, own.errorCode())
        assertEquals(Triple("acme.enabled", JsonPrimitive(false), ConfigTarget.USER), settings.writes.single())
        assertEquals("E_CAPABILITY", harness.call(plain, "config.set", buildJsonObject { put("key", "editor.tabSize"); put("value", 2) }).errorCode())
        assertEquals(JsonPrimitive(false), harness.call(plain, "config.get", buildJsonObject { put("key", "acme.enabled") })["result"])

        val trusted = harness.extension(tmp.newFolder(), listOf("ui.settings")).copy(id = "acme.trusted")
        harness.activate(trusted)
        val project = harness.call(trusted, "config.set", buildJsonObject { put("key", "editor.tabSize"); put("value", 2); put("target", "project") })
        assertEquals(null, project.errorCode())
        assertEquals(ConfigTarget.PROJECT, settings.writes.last().third)
        // The router's protected list and the L1 unwritable list both hold, whatever is granted.
        assertEquals("E_CAPABILITY", harness.call(trusted, "config.set", buildJsonObject { put("key", "extensions.wasm.enabled"); put("value", false) }).errorCode())
        assertEquals("E_CAPABILITY", harness.call(trusted, "config.set", buildJsonObject { put("key", "lsp.servers.pyright"); put("value", 1) }).errorCode())
        assertEquals("E_ARGS", harness.call(trusted, "config.set", buildJsonObject { put("key", "editor.tabSize"); put("value", 2); put("target", "galaxy") }).errorCode())
        assertEquals(2, settings.writes.size)
        harness.host.close()
    }

    @Test fun `environment and project targets need an open workspace`() {
        val config = WasmConfigPort(FakeSettings()) { SettingsQuery(null, null, null) }
        assertEquals(ErrorCode.E_UNAVAILABLE, code { config.set("acme.a", "acme.k", JsonPrimitive(1), "project") })
        assertEquals(ErrorCode.E_UNAVAILABLE, code { config.set("acme.a", "acme.k", JsonPrimitive(1), "language") })
        assertEquals(null, code { config.set("acme.a", "acme.k", JsonPrimitive(1), null) })
    }

    // ---- editor, lsp, clipboard

    @Test fun `editor reads the active document and edits only inside the workspace`() = runBlocking {
        val bridge = FakeBridge(document = ActiveDocument("/workspace/a.py", "python", 3, "one\ntwo three", 4, 7, true))
        val editor = WasmEditorPort { bridge }
        val active = editor.active()!!.jsonObject
        assertEquals("/workspace/a.py", active["path"]!!.jsonPrimitive.content)
        assertEquals(3, active["version"]!!.jsonPrimitive.content.toInt())
        assertEquals("""{"start":{"line":1,"character":0},"end":{"line":1,"character":3}}""", active["selections"]!!.jsonArray.single().toString())
        val range = buildJsonObject {
            put("start", buildJsonObject { put("line", 1); put("character", 4) })
            put("end", buildJsonObject { put("line", 1); put("character", 9) })
        }
        assertEquals("three", editor.getText(range))
        assertEquals(JsonPrimitive(true), editor.applyEdits("acme.x", JsonArray(listOf(buildJsonObject { put("range", range); put("newText", "3") }))))
        assertEquals("/workspace/a.py", bridge.edits.single().single().path)
        val outside = JsonArray(listOf(buildJsonObject { put("path", "/etc/passwd"); put("range", range); put("newText", "x") }))
        assertEquals(ErrorCode.E_CAPABILITY, code { editor.applyEdits("acme.x", outside) })
        bridge.document = null
        assertNull(editor.active())
        assertEquals(ErrorCode.E_UNAVAILABLE, code { editor.getText(null) })
        assertEquals(ErrorCode.E_UNAVAILABLE, code { WasmEditorPort { null }.getText(null) })
    }

    @Test fun `lsp requests go through the workspace gateway and report status from context keys`() = runBlocking {
        val bridge = FakeBridge().apply { lspAnswer = dev.easyide.extensions.action.LspOutcome.Unavailable("no server") }
        val lsp = WasmLspPort({ bridge }) { key -> if (key == "lspState:python") JsonPrimitive("ready") else null }
        assertEquals(ErrorCode.E_UNAVAILABLE, code { lsp.request("python", "textDocument/hover", null) })
        assertEquals("""{"state":"ready","ready":false}""", lsp.status("python").toString())
    }

    @Test fun `clipboard goes through the injected clipboard`() = runBlocking {
        val clip = object : ClipboardAccess {
            var text: String? = null
            override fun read() = text
            override fun write(text: String) { this.text = text }
        }
        val port = WasmClipboardPort(clip, Dispatchers.Unconfined)
        port.write("copied")
        assertEquals("copied", port.read())
    }

    @Test fun `memory pressure from running low on drops instances`() {
        assertEquals(false, WasmRuntime.shouldTrim(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(true, WasmRuntime.shouldTrim(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(true, WasmRuntime.shouldTrim(android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
    }

    @Test fun `a module's sha is recorded on first load and kept`() {
        val module = tmp.newFile("m.wasm").apply { writeBytes(byteArrayOf(0, 97, 115, 109)) }
        val shas = ModuleShas(File(tmp.root, "rec.json"))
        val first = shas.shaFor(module)
        module.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(first, ModuleShas(File(tmp.root, "rec.json")).shaFor(module))
        module.delete()
        shas.prune()
        if (File(tmp.root, "rec.json").readText().contains("m.wasm")) fail("pruned record kept a deleted module")
    }

    private companion object {
        const val QUOTA = 2048L
        const val SPARE = 50L
    }
}
