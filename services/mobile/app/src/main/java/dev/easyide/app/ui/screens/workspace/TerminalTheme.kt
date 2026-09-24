package dev.easyide.app.ui.screens.workspace

import androidx.compose.ui.graphics.toArgb
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import dev.easyide.app.ui.theme.TerminalPalette
import java.util.WeakHashMap

/**
 * Pushes the theme's [TerminalPalette] into the vendored Termux emulator
 * without modifying it.
 *
 * Termux keeps its default colours in one process-wide scheme
 * ([TerminalColors.COLOR_SCHEME]): every new emulator copies it at creation, and
 * a program's reset sequences (RIS, OSC 104) restore from it. Writing the
 * palette there is therefore what makes new tabs and resets come out themed;
 * emulators that already exist copy the new defaults with `mColors.reset()`.
 * That reset also drops colours a program set with OSC 4, so it runs only when
 * that emulator's palette actually changes, never on an ordinary recomposition.
 *
 * Main thread only, like the emulator itself (output is processed on the main
 * looper), so neither the scheme nor [appliedTo] needs a lock.
 */
internal object TerminalTheme {

    private var schemePalette: TerminalPalette? = null

    /** Weak keys: a closed tab's emulator must not be kept alive by its colour record. */
    private val appliedTo = WeakHashMap<TerminalEmulator, TerminalPalette>()

    /**
     * Makes [palette] the default for new emulators and applies it to [emulator]
     * (null before the view's first layout creates one). Returns whether the
     * emulator's colours changed, i.e. whether the view needs a redraw.
     */
    fun apply(palette: TerminalPalette, emulator: TerminalEmulator?): Boolean {
        if (schemePalette != palette) {
            writeDefaults(palette, TerminalColors.COLOR_SCHEME.mDefaultColors)
            schemePalette = palette
        }
        if (emulator == null || appliedTo[emulator] == palette) return false
        emulator.mColors.reset()
        appliedTo[emulator] = palette
        return true
    }

    /**
     * Writes the 16 ANSI slots and the default fg/bg/cursor into a Termux colour
     * table ([TextStyle.NUM_INDEXED_COLORS] entries). Slots 16-255, the xterm
     * 256-colour cube, are left as the emulator defines them.
     */
    fun writeDefaults(palette: TerminalPalette, table: IntArray) {
        palette.ansi.forEachIndexed { index, color -> table[index] = color.toArgb() }
        table[TextStyle.COLOR_INDEX_FOREGROUND] = palette.foreground.toArgb()
        table[TextStyle.COLOR_INDEX_BACKGROUND] = palette.background.toArgb()
        table[TextStyle.COLOR_INDEX_CURSOR] = palette.cursor.toArgb()
    }
}
