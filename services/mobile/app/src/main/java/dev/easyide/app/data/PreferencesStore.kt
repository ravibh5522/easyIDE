package dev.easyide.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dev.easyide.app.data.settings.LegacySettingsMigration

/**
 * The app's single preferences file. Shared by [UiPreferences] (app state) and
 * [dev.easyide.app.data.settings.SettingsStore] (user settings) because
 * DataStore forbids two instances on one file within a process.
 */
internal val Context.preferencesStore: DataStore<Preferences> by preferencesDataStore(
    name = "ui_preferences",
    produceMigrations = { listOf(LegacySettingsMigration) },
)
