package dev.easyide.app.ui.shell.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.shell.LayoutPreset
import dev.easyide.app.ui.shell.LayoutPresets
import dev.easyide.app.ui.shell.ShellState

/**
 * The layout picker (Ctrl+Alt+P): Automatic, which follows the window, and every preset offered for
 * this window's arrangement, the built-in ones first and then those extensions offer ([extra]). The choice is kept with the project's layout, so reopening it brings the
 * same arrangement back.
 */
@Composable
fun LayoutPresetPicker(state: ShellState, extra: List<LayoutPreset>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val chosen = state.current.layout.preset
    val offered = LayoutPresets.offered(state.arrangement, extra)
    KitDialog(
        title = stringResource(R.string.wshell_preset_title),
        onDismiss = onDismiss,
        dismiss = KitAction(stringResource(R.string.wshell_preset_close), onDismiss),
    ) {
        KitRow(
            title = stringResource(R.string.wshell_preset_auto),
            subtitle = stringResource(R.string.wshell_preset_auto_hint, LayoutPresets.auto(state.arrangement).title),
            selected = chosen == LayoutPresets.AUTO,
            onClick = { onPick(LayoutPresets.AUTO); onDismiss() },
            id = "preset-auto",
        )
        offered.forEach { preset ->
            KitRow(preset.title, selected = chosen == preset.id, onClick = { onPick(preset.id); onDismiss() }, id = "preset-${preset.id}")
        }
    }
}
