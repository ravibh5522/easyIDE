package dev.easyide.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.io.IOException

/** Where the shell keeps its one snapshot; an interface so the shell's tests need no Android storage. */
interface ShellStorage {
    suspend fun read(): String?
    suspend fun write(snapshot: String)
}

/**
 * The app-scope shell snapshot (`ShellSnapshot.encodeApp`) as one string in the app's preferences
 * file, next to [UiPreferences]: it is UI state that belongs to no project and needs no export.
 */
class ShellStateStore(private val context: Context) : ShellStorage {

    override suspend fun read(): String? = try {
        context.preferencesStore.data.first()[KEY]
    } catch (_: IOException) {
        null // boundary: an unreadable file starts the shell from its default, never fails the launch
    }

    override suspend fun write(snapshot: String) {
        context.preferencesStore.edit { it[KEY] = snapshot }
    }

    private companion object {
        val KEY = stringPreferencesKey("shell_app_snapshot")
    }
}
