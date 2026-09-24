package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.kit.HapticEvent
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.collectFlags
import dev.easyide.app.ui.kit.kitFocusRing
import dev.easyide.app.ui.kit.kitStateLayer
import dev.easyide.app.ui.kit.rememberHaptics
import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.extensions.contrib.RowKey

/**
 * An accessory key row: the terminal's (between the scrollback and the keyboard)
 * or a contributed editor row. [keys] come from the active key row (built-in or
 * contributed, see `KeyRows`); a tap runs a key's action, a long press its
 * `longPress` action when it has one.
 *
 * Scrolls horizontally rather than wrapping: a second row would eat screen the
 * terminal needs, and rows put the keys worth reaching first on the left.
 */
@Composable
fun KeyRowBar(
    keys: List<RowKey>,
    onKey: (KeyAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Kit.colors.panel)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Kit.space.xs, vertical = Kit.space.xs),
        horizontalArrangement = Arrangement.spacedBy(Kit.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.forEach { key ->
            KeyCap(key = key, onClick = { onKey(key.action) }, onLongClick = key.longPress?.let { lp -> { onKey(lp) } })
        }
    }
}

/**
 * A raised key the key tokens tall (a phone's at least the touch floor); a long press runs the key's `longPress` action. Pressing washes it with the
 * state layer and plays the key-tap haptic (which honours `appearance.haptics`), so a tap
 * registers the way a soft-keyboard key does even when the shell prints nothing back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyCap(key: RowKey, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val colors = Kit.colors
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val flags = interaction.collectFlags()
    val shape = RoundedCornerShape(Kit.radius.s)
    // A phone's keys are isolated thumb targets and keep the touch floor; a wide window has the tokens.
    val floor = if (Kit.metrics.width.isCompact) Kit.metrics.touchFloor else 0.dp

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = maxOf(Kit.control.keyMinWidth, floor), minHeight = maxOf(Kit.control.keyHeight, floor))
            .clip(shape)
            .background(colors.raised)
            .border(Kit.hairline, colors.panelBorder, shape)
            .kitStateLayer(flags, true, colors.plainText)
            .kitFocusRing(flags.focused, shape)
            .semantics(mergeDescendants = true) {}
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onLongClick = onLongClick?.let { lp -> { haptics.play(HapticEvent.LongPress); lp() } },
            ) {
                haptics.play(HapticEvent.KeyTap)
                onClick()
            }
            .padding(horizontal = Kit.space.s),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(key.label, style = Kit.text.mono.copy(color = colors.plainText), maxLines = 1)
    }
}
