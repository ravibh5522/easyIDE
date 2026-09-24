package dev.easyide.app.ui.foundation

import androidx.compose.runtime.compositionLocalOf
import dev.easyide.app.ui.commands.Keymap

/**
 * The effective keymap (built-in table + the active profile's keybindings.json),
 * provided once by MainActivity so dispatch and the palette's chord labels
 * always agree and follow edits without a restart.
 */
val LocalKeymap = compositionLocalOf { Keymap.DEFAULT }
