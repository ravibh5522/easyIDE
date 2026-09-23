package dev.easyide.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.easyide.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

class SettingsStoreTest {

    @Test
    fun everyDefaultIsValid() {
        SettingsSchema.all.forEach { setting ->
            @Suppress("UNCHECKED_CAST")
            val s = setting as Setting<Any?>
            assertEquals(setting.key, s.default, s.decode(s.encode(s.default)))
        }
    }

    @Test
    fun keysAreUnique() {
        assertEquals(SettingsSchema.all.size, SettingsSchema.all.map { it.key }.toSet().size)
        assertEquals(SettingsSchema.all.size, SettingsSchema.all.map { it.storeKey }.toSet().size)
    }

    @Test
    fun missingValuesResolveToDefaults() = runBlocking {
        val store = SettingsStore(FakeDataStore())
        assertEquals(13, store.get(SettingsSchema.editorFontSize).first())
        assertEquals(ThemeMode.SYSTEM_DEFAULT, store.get(SettingsSchema.themeMode).first())
    }

    @Test
    fun legacyThemeKeyIsReadWithoutMigration() = runBlocking {
        val store = SettingsStore(FakeDataStore(preferencesOf(stringPreferencesKey("theme_mode") to "AMOLED_BLACK")))
        assertEquals(ThemeMode.AMOLED_BLACK, store.get(SettingsSchema.themeMode).first())
    }

    @Test
    fun setThenResetRoundTrips() = runBlocking {
        val fake = FakeDataStore()
        val store = SettingsStore(fake)
        store.set(SettingsSchema.terminalFontSize, 18)
        store.set(SettingsSchema.themeMode, ThemeMode.DARK)
        assertEquals(18, store.get(SettingsSchema.terminalFontSize).first())
        assertEquals("DARK", fake.data.value[stringPreferencesKey("theme_mode")])
        assertTrue(store.snapshot.first().isModified(SettingsSchema.terminalFontSize))

        store.reset(SettingsSchema.terminalFontSize)
        assertEquals(13, store.get(SettingsSchema.terminalFontSize).first())
        assertFalse(store.snapshot.first().isModified(SettingsSchema.terminalFontSize))
    }

    @Test
    fun invalidStoredValuesFallBackToDefault() = runBlocking {
        val store = SettingsStore(
            FakeDataStore(
                preferencesOf(
                    intPreferencesKey("editor.fontSize") to 500,
                    stringPreferencesKey("editor.lineHeight") to "tall",
                    stringPreferencesKey("theme_mode") to "NEON",
                )
            )
        )
        val snapshot = store.snapshot.first()
        assertEquals(13, snapshot[SettingsSchema.editorFontSize])
        assertEquals(20, snapshot[SettingsSchema.editorLineHeight])
        assertEquals(ThemeMode.SYSTEM_DEFAULT, snapshot[SettingsSchema.themeMode])
        assertFalse(snapshot.isModified(SettingsSchema.editorFontSize))
    }
}
