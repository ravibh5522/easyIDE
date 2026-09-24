package dev.easyide.app.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreTest {

    private val user = MemoryLayer(obj("{\"editor.fontSize\": 14}"), "profile default")
    private val env = MemoryLayer(obj("{\"editor.fontSize\": 15}"), "env file")
    private val project = MemoryLayer(
        obj(
            """{"editor.fontSize": 16, "[python]": {"editor.fontSize": 18},
                "lsp.servers": {"zls": {"languages": ["zig"], "command": ["zls"], "memoryBudgetMb": 300}}}""",
        ),
        "project file",
    )
    private val registry = SettingsRegistry(SettingsSchema.all)
    private val prefs = FakeDataStore()
    private val trust = ProjectTrust(prefs)
    private val safe = MutableStateFlow<SafeModeReason?>(null)
    private val store = SettingsStore(user, registry, { env }, { project }, trust, safe, NO_SINK)
    private val q = SettingsQuery(envId = "e", projectId = "p")

    @Test
    fun projectBeatsEnvironmentBeatsUser() = runTest {
        assertEquals(14, store.snapshot.first()[SettingsSchema.editorFontSize])
        assertEquals(15, store.snapshot(SettingsQuery(envId = "e")).first()[SettingsSchema.editorFontSize])
        val s = store.snapshot(q).first()
        assertEquals(16, s[SettingsSchema.editorFontSize])
        assertEquals(18, s.get(SettingsSchema.editorFontSize, "python"))
        assertEquals("project file", s.inspect(SettingsSchema.editorFontSize).winner.source)
    }

    @Test
    fun aBatchEditLandsInOneSnapshot() = runTest {
        store.snapshot(q).first { it[SettingsSchema.editorFontSize] == 16 }
        project.write(listOf(SettingEdit("editor.fontSize", null, json("20")), SettingEdit("editor.lineHeight", null, json("30"))))
        val next = store.snapshot(q).first { it[SettingsSchema.editorFontSize] == 20 }
        // Both values of the batch are in the first snapshot showing either.
        assertEquals(30, next[SettingsSchema.editorLineHeight])
    }

    @Test
    fun untrustedProjectExecValuesAreDropped() = runTest {
        fun servers(s: SettingsSnapshot) = s.layer(LayerId.PROJECT)!!.doc.plain["lsp.servers"].toString()
        val untrusted = store.snapshot(q).first()
        assertTrue(servers(untrusted).contains("memoryBudgetMb"))
        assertTrue(!servers(untrusted).contains("command"))
        val request = store.trustRequest("p").first()!!
        assertEquals(TrustState.PENDING, request.state)
        assertEquals("zls", request.items.single().key)

        trust.allow("p", request.fingerprint)
        assertTrue(servers(store.snapshot(q).first()).contains("command"))
        assertEquals(TrustState.TRUSTED, store.trustRequest("p").first()!!.state)

        // Safe mode ignores project exec values even when trusted.
        safe.value = SafeModeReason.LAUNCHER_SHORTCUT
        assertTrue(!servers(store.snapshot(q).first()).contains("command"))
        safe.value = null

        // Any change to what would run asks again.
        project.write(listOf(SettingEdit("lsp.servers", null, json("{\"zls\": {\"languages\": [\"zig\"], \"command\": [\"zls\", \"--evil\"]}}"))))
        assertEquals(TrustState.PENDING, store.trustRequest("p").first()!!.state)
    }

    @Test
    fun trustFingerprintIgnoresNonExecEditsAndKeyOrder() {
        val schema = registry.state.value
        val a = ExecBearing.fingerprint(ExecBearing.extract(LayerDoc.fromJson(obj("{\"lsp.servers\": {\"s\": {\"command\": [\"x\"], \"env\": {\"A\": \"1\", \"B\": \"2\"}, \"memoryBudgetMb\": 1}}}")), schema))
        val b = ExecBearing.fingerprint(ExecBearing.extract(LayerDoc.fromJson(obj("{\"editor.fontSize\": 20, \"lsp.servers\": {\"s\": {\"memoryBudgetMb\": 9, \"env\": {\"B\": \"2\", \"A\": \"1\"}, \"command\": [\"x\"]}}}")), schema))
        val c = ExecBearing.fingerprint(ExecBearing.extract(LayerDoc.fromJson(obj("{\"lsp.servers\": {\"s\": {\"command\": [\"x\"], \"env\": {\"A\": \"2\", \"B\": \"2\"}}}}")), schema))
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertNull(ExecBearing.fingerprint(ExecBearing.extract(LayerDoc.fromJson(obj("{\"editor.fontSize\": 20}")), schema)))
    }

    @Test
    fun trustDecisionsDenyAndDefer() = runTest {
        assertEquals(TrustState.PENDING, trust.state("p", "f1").first())
        trust.defer("p", "f1")
        assertEquals(TrustState.DEFERRED, trust.state("p", "f1").first())
        trust.deny("p", "f1")
        assertEquals(TrustState.DENIED, trust.state("p", "f1").first())
        assertEquals(TrustState.PENDING, trust.state("p", "f2").first())
        trust.reset("p")
        assertEquals(TrustState.PENDING, trust.state("p", "f1").first())
        assertEquals(TrustState.NOT_REQUIRED, trust.state("p", null).first())
    }

    @Test
    fun extensionsCannotWriteProtectedOrForeignKeys() = runTest {
        val ext = SettingsWriter.Extension("acme", mayWriteForeign = true)
        for (key in listOf("extensions.safeMode", "extensions.disabled", "lsp.servers", "profiles.active")) {
            val r = store.write(ConfigTarget.USER, listOf(SettingEdit(key, null, json("true"))), ext)
            assertEquals(key, WriteRefusal.PROTECTED_KEY, (r.exceptionOrNull() as SettingsRefusedException).refusal)
        }
        val foreign = store.write(ConfigTarget.USER, listOf(SettingEdit("editor.fontSize", null, json("20"))), SettingsWriter.Extension("acme", false))
        assertEquals(WriteRefusal.FOREIGN_KEY, (foreign.exceptionOrNull() as SettingsRefusedException).refusal)
        assertTrue(store.write(ConfigTarget.USER, listOf(SettingEdit("editor.fontSize", null, json("20"))), ext).isSuccess)
        assertEquals(20, store.snapshot.first()[SettingsSchema.editorFontSize])
    }

    @Test
    fun writesAreCheckedAgainstScopeAndSchema() = runTest {
        val projectTarget = ConfigTarget(LayerId.PROJECT, projectId = "p")
        fun refusal(r: Result<Unit>) = (r.exceptionOrNull() as SettingsRefusedException).refusal
        assertEquals(WriteRefusal.SCOPE, refusal(store.set(SettingsSchema.terminalFontSize, 12, projectTarget)))
        assertEquals(WriteRefusal.NOT_LANGUAGE_OVERRIDABLE, refusal(store.set(SettingsSchema.terminalFontSize, 12, language = "python")))
        assertEquals(WriteRefusal.INVALID_VALUE, refusal(store.write(ConfigTarget.USER, listOf(SettingEdit("editor.fontSize", null, json("500"))))))
        assertEquals(WriteRefusal.PROTECTED_KEY, refusal(store.write(projectTarget, listOf(SettingEdit("profiles.active", null, json("\"x\""))))))
        assertEquals(WriteRefusal.BAD_KEY, refusal(store.write(ConfigTarget.USER, listOf(SettingEdit("[python]", null, json("{}"))))))
        assertEquals(WriteRefusal.NOT_WRITABLE_LAYER, refusal(store.write(ConfigTarget(LayerId.EXTENSION), emptyList())))
        assertTrue(store.set(SettingsSchema.editorFontSize, 22, projectTarget, language = "go").isSuccess)
        assertEquals(22, store.snapshot(q).first().get(SettingsSchema.editorFontSize, "go"))
    }

    @Test
    fun resetAndResetAllKeepAppLevelKeys() = runTest {
        store.set(SettingsSchema.safeMode, true)
        store.set(SettingsSchema.editorLineHeight, 30, language = "python")
        store.reset(SettingsSchema.editorFontSize)
        assertEquals(SettingsSchema.editorFontSize.default, store.snapshot.first()[SettingsSchema.editorFontSize])
        store.resetAll(ConfigTarget.USER)
        val doc = user.state.value
        assertEquals(setOf("extensions.safeMode"), doc.plain.keys)
        assertTrue(doc.lang.isEmpty())
    }
}
