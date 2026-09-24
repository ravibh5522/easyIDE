package dev.easyide.app.ui.foundation

import androidx.compose.runtime.compositionLocalOf
import dev.easyide.app.data.settings.SettingsSnapshot

/**
 * Resolved settings for the whole UI, provided once by MainActivity so a
 * consumer (editor text style, terminal font) reads a value with a map lookup
 * instead of each collecting its own DataStore flow.
 */
val LocalSettings = compositionLocalOf { SettingsSnapshot.DEFAULTS }
