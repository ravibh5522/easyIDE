package dev.easyide.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Resolved values for every setting at one moment. Immutable, so Compose can
 * read it from a CompositionLocal with a plain lookup per use.
 */
class SettingsSnapshot(private val user: Map<String, Any?>) {

    operator fun <T> get(setting: Setting<T>): T = setting.resolve(listOf(user[setting.storeKey]))

    /** True when the user layer holds a valid value, i.e. "reset" would change something. */
    fun isModified(setting: Setting<*>): Boolean = setting.decode(user[setting.storeKey]) != null

    companion object {
        val DEFAULTS = SettingsSnapshot(emptyMap())
    }
}

/**
 * The user settings layer, over the app's one preferences DataStore (shared
 * with [dev.easyide.app.data.UiPreferences]; DataStore forbids two instances on
 * one file).
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    val snapshot: Flow<SettingsSnapshot> = dataStore.data
        .catch { cause ->
            // Boundary: an unreadable file means defaults, not a crash on launch.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs -> SettingsSnapshot(prefs.asMap().mapKeys { it.key.name }) }

    fun <T> get(setting: Setting<T>): Flow<T> = snapshot.map { it[setting] }.distinctUntilChanged()

    suspend fun <T> set(setting: Setting<T>, value: T) {
        // The cast is safe by construction: encode() returns exactly the type
        // each subclass's prefKey declares.
        @Suppress("UNCHECKED_CAST")
        val key = setting.prefKey as Preferences.Key<Any>
        dataStore.edit { it[key] = setting.encode(value) }
    }

    suspend fun reset(setting: Setting<*>) {
        dataStore.edit { it.remove(setting.prefKey) }
    }
}
