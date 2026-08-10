package dev.tabcode.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.tabcode.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.preferencesStore: DataStore<Preferences> by preferencesDataStore(name = "ui_preferences")

/** UI-layer preferences. Sandbox state lives separately, owned by :sandbox-runtime. */
class UiPreferences(private val context: Context) {

    private val flow: Flow<Preferences> = context.preferencesStore.data
        .catch { cause ->
            // Boundary: unreadable prefs fall back to defaults rather than
            // taking down the app on launch.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }

    val themeMode: Flow<ThemeMode> = flow.map { prefs ->
        val stored = prefs[KEY_THEME_MODE]
        ThemeMode.entries.find { it.name == stored } ?: ThemeMode.SYSTEM_DEFAULT
    }

    val onboardingComplete: Flow<Boolean> = flow.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETE] ?: false
    }

    /**
     * Environment pre-selected when creating a project. Null means "no default
     * chosen"; the new-project screen then falls back to the first available.
     */
    val defaultEnvironmentId: Flow<String?> = flow.map { prefs ->
        prefs[KEY_DEFAULT_ENVIRONMENT]?.takeIf { it.isNotEmpty() }
    }

    /**
     * SAF tree URI suggested as the storage location for new projects. Null
     * means "app storage" - projects then live only in app-private storage,
     * as before this setting existed.
     */
    val defaultProjectsFolderUri: Flow<String?> = flow.map { prefs ->
        prefs[KEY_DEFAULT_PROJECTS_FOLDER]?.takeIf { it.isNotEmpty() }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.preferencesStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.preferencesStore.edit { it[KEY_ONBOARDING_COMPLETE] = complete }
    }

    suspend fun setDefaultEnvironmentId(id: String?) {
        context.preferencesStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_DEFAULT_ENVIRONMENT) else prefs[KEY_DEFAULT_ENVIRONMENT] = id
        }
    }

    suspend fun setDefaultProjectsFolderUri(uri: String?) {
        context.preferencesStore.edit { prefs ->
            if (uri == null) prefs.remove(KEY_DEFAULT_PROJECTS_FOLDER) else prefs[KEY_DEFAULT_PROJECTS_FOLDER] = uri
        }
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val KEY_DEFAULT_ENVIRONMENT = stringPreferencesKey("default_environment_id")
        val KEY_DEFAULT_PROJECTS_FOLDER = stringPreferencesKey("default_projects_folder_uri")
    }
}
