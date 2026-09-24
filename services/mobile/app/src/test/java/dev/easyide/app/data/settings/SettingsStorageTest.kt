package dev.easyide.app.data.settings

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStorageTest {

    @Test
    fun fileLayerKeepsLastGoodParseOnError() = runTest {
        val io = FakeIo("{\"editor.fontSize\": 20}")
        val source = FileLayerSource(io, backgroundScope)
        assertEquals(json("20"), source.current().plain["editor.fontSize"])
        io.text = "{\"editor.fontSize\": "
        source.reload()
        val doc = source.current()
        assertEquals(json("20"), doc.plain["editor.fontSize"])
        assertEquals(DiagnosticCode.PARSE_ERROR, doc.errors.single().code)
        io.text = "{}"
        source.reload()
        assertTrue(source.current().plain.isEmpty())
        assertTrue(source.current().errors.isEmpty())
    }

    @Test
    fun fileLayerWritesMinimalEditsAndRefusesBrokenFiles() = runTest {
        val io = FakeIo("{\n  // mine\n  \"a\": 1\n}")
        val source = FileLayerSource(io, backgroundScope)
        assertTrue(source.write(listOf(SettingEdit("editor.fontSize", "python", json("18")))).isSuccess)
        assertTrue(io.text!!.contains("// mine"))
        assertEquals(json("18"), source.current().lang["python"]!!["editor.fontSize"])

        io.text = "{ broken"
        val refused = source.write(listOf(SettingEdit("a", null, json("2"))))
        assertEquals(DiagnosticCode.PARSE_ERROR, (refused.exceptionOrNull() as SettingsWriteException).diagnostic?.code)
        assertEquals("{ broken", io.text)
        assertTrue(source.writeText("{ still broken").isFailure)
        assertTrue(source.writeText("{\"b\": true}").isSuccess)
        assertEquals(setOf("b"), source.current().plain.keys)
    }

    @Test
    fun ownWriteIsNotReloadedAsAChange() = runTest {
        val io = FakeIo("{}")
        val source = FileLayerSource(io, backgroundScope)
        source.write(listOf(SettingEdit("a", null, json("1"))))
        val version = source.current().version
        source.reload()
        assertEquals(version, source.current().version)
    }

    @Test
    fun oversizedFilesAreNotParsed() = runTest {
        val io = object : SettingsFileIo {
            override val path = "big"
            override suspend fun read() = FileRead.TooLarge(SettingsPolicy.MAX_FILE_BYTES + 1)
            override suspend fun write(text: String) = Result.success(Unit)
        }
        val doc = FileLayerSource(io, backgroundScope).current()
        assertEquals(JsoncError.TOO_LARGE, doc.errors.single().parseError)
    }

    @Test
    fun dataStoreLayerRoundTripsPlainAndLanguageValuesInOneEdit() = runTest {
        val store = FakeDataStore()
        val layer = DataStoreUserLayer(store)
        layer.write(
            listOf(
                SettingEdit("editor.fontSize", null, json("15")),
                SettingEdit("editor.fontSize", "python", json("17")),
                SettingEdit("x.obj", null, json("{\"a\": [1]}")),
            ),
        )
        assertEquals(1, store.edits)
        val doc = layer.doc.first()
        assertEquals(json("15"), doc.plain["editor.fontSize"])
        assertEquals(json("17"), doc.lang["python"]!!["editor.fontSize"])
        assertEquals("{\"a\":[1]}", store.data.value[stringPreferencesKey("setting:x.obj")])

        layer.write(listOf(SettingEdit("editor.fontSize", "python", null)))
        assertTrue(layer.doc.first().lang.isEmpty())
        assertTrue(layer.writeText("{\"a\": ").isFailure)
        assertTrue(layer.writeText("{\"b\": 1, \"[go]\": {\"c\": 2}}").isSuccess)
        assertEquals(setOf("b"), layer.doc.first().plain.keys)
    }

    @Test
    fun replaceKeepsKeysItDoesNotOwn() = runTest {
        val layer = DataStoreUserLayer(FakeDataStore())
        layer.write(listOf(SettingEdit("extensions.safeMode", null, json("true")), SettingEdit("editor.fontSize", null, json("15"))))
        layer.replace(LayerDoc.fromJson(obj("{\"terminal.fontSize\": 12}"))) { !SettingsPolicy.isAppLevel(it) }
        assertEquals(setOf("extensions.safeMode", "terminal.fontSize"), layer.doc.first().plain.keys)
    }

    @Test
    fun legacyKeysMigrate() = runTest {
        val old = preferencesOf(
            stringPreferencesKey("theme_mode") to "AMOLED_BLACK",
            intPreferencesKey("editor.fontSize") to 18,
            stringPreferencesKey("onboarding_complete") to "kept",
        )
        assertTrue(LegacySettingsMigration.shouldMigrate(old))
        val migrated = LegacySettingsMigration.migrate(old)
        assertFalse(LegacySettingsMigration.shouldMigrate(migrated))
        assertEquals("\"AMOLED_BLACK\"", migrated[stringPreferencesKey("setting:appearance.themeMode")])
        assertEquals("18", migrated[stringPreferencesKey("setting:editor.fontSize")])
        assertEquals("kept", migrated[stringPreferencesKey("onboarding_complete")])
        val snapshot = SettingsSnapshot(
            SchemaState.builtInOnly(SettingsSchema.all),
            listOf(Layer(LayerId.USER, DataStoreUserLayer(FakeDataStore(migrated)).doc.first(), "u")),
        )
        assertEquals(dev.easyide.app.ui.theme.ThemeMode.AMOLED_BLACK, snapshot[SettingsSchema.themeMode])
        assertEquals(18, snapshot[SettingsSchema.editorFontSize])
    }

    @Test
    fun safeModeStateCombinesSettingAndSession() = runTest {
        val layer = DataStoreUserLayer(FakeDataStore())
        val safe = SafeModeState(layer)
        assertEquals(null, safe.active.first())
        layer.write(listOf(SettingEdit(SettingsSchema.safeMode.key, null, json("true"))))
        assertEquals(SafeModeReason.SETTING, safe.active.first())
        safe.enterForSession(SafeModeReason.LAUNCHER_SHORTCUT)
        assertEquals(SafeModeReason.LAUNCHER_SHORTCUT, safe.active.first())
        assertTrue(safe.exit().isSuccess)
        assertEquals(null, safe.active.first())
        assertFalse(layer.doc.first().plain.containsKey(SettingsSchema.safeMode.key))
    }
}
