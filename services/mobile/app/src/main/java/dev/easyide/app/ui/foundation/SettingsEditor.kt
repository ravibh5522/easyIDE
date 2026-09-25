package dev.easyide.app.ui.foundation

import androidx.compose.runtime.staticCompositionLocalOf
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Lets UI that changes a setting as a side effect of a gesture (pinch to zoom the editor font,
 * a toggle in the explorer header) write to the user layer without holding a ViewModel.
 * Provided once by MainActivity next to [LocalSettings]; a refused or failed write leaves the
 * stored value as it was, which the next snapshot shows.
 */
class SettingsEditor(private val store: SettingsStore, private val scope: CoroutineScope) {

    /** Writes [value] to the user layer, inside [language]'s `[lang]` block when given. */
    fun <T> set(setting: Setting<T>, value: T, language: String? = null) {
        scope.launch { store.set(setting, value, language = language) }
    }
}

/** Null in previews and tests without an app container. */
val LocalSettingsEditor = staticCompositionLocalOf<SettingsEditor?> { null }
