package dev.easyide.app.extensions.adapters

import dev.easyide.app.ui.commands.KeyBinding
import dev.easyide.app.ui.commands.KeyFocus
import dev.easyide.app.ui.commands.KeyNames
import dev.easyide.extensions.contrib.KeybindingContribution
import dev.easyide.extensions.contrib.Owned
import dev.easyide.extensions.whenclause.ContextKeys

/**
 * `contributes.keybindings` -> the keymap's extension layer (EXT-24). `linux` replaces
 * `key` when present, `mac`/`win` are ignored, `cmd` is Meta (sdk-reference).
 *
 * Terminal focus: a contributed chord reaches a focused terminal only when its `when`
 * mentions `terminalFocus`. Otherwise a pack binding `ctrl+r` for its editor would steal
 * reverse-search from every shell; VS Code has the same default (commands must opt in to
 * skipping the shell).
 */
object ContributedKeybindings {

    fun bindings(entries: List<Owned<KeybindingContribution>>, onSkipped: (Owned<KeybindingContribution>, String) -> Unit): List<KeyBinding> =
        entries.mapNotNull { owned ->
            val k = owned.value
            val text = k.linux ?: k.key
            val keys = KeyNames.parseSequence(text)
            if (keys == null) {
                onSkipped(owned, "keybinding '$text' for ${k.command} is not a key press or two-press chord this device can dispatch")
                return@mapNotNull null
            }
            KeyBinding(
                chord = keys.chord,
                prefix = keys.prefix,
                command = k.command,
                focus = if (k.`when`?.keys?.contains(ContextKeys.terminalFocus.name) == true) KeyFocus.ANYWHERE else KeyFocus.OUTSIDE_TERMINAL,
                whenExpr = k.`when`,
                args = k.args,
            )
        }
}
