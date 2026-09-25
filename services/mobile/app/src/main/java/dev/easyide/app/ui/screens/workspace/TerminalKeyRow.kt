package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Size
import dev.easyide.app.ui.kit.HapticEvent
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.collectFlags
import dev.easyide.app.ui.kit.kitFocusRing
import dev.easyide.app.ui.kit.kitStateLayer
import dev.easyide.app.ui.kit.rememberHaptics
import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.extensions.contrib.RowKey

/**
 * An accessory key row: the terminal's (between the scrollback and the keyboard) or a contributed
 * editor row. [keys] come from the active key row (built-in or contributed, see `KeyRows`); a tap
 * runs a key's action, a long press its `longPress` action when it has one.
 *
 * One flat strip the toolbar-button token tall: keys are plain labels split by hairlines, not boxed
 * chips, and [trailing] (the dock's `more`) stays at the end while the keys scroll. It scrolls
 * rather than wraps: a second row would eat screen the editor needs, and rows put the keys worth
 * reaching first on the left.
 */
@Composable
fun KeyRowBar(
    keys: List<RowKey>,
    onKey: (KeyAction) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().height(Kit.control.toolbarButton).background(Kit.colors.panel), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            keys.forEach { key -> KeyCap(key, { onKey(key.action) }, key.longPress?.let { lp -> { onKey(lp) } }) }
        }
        trailing()
    }
}

/**
 * A flat key with a hairline on its right edge; a long press runs the key's `longPress` action.
 * Pressing washes it with the state layer and plays the key-tap haptic (which honours
 * `appearance.haptics`), so a tap registers the way a soft-keyboard key does even when the shell
 * prints nothing back. A phone's keys keep the touch floor in width; a wide window has the token.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyCap(key: RowKey, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val colors = Kit.colors
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val flags = interaction.collectFlags()
    val floor = if (Kit.metrics.width.isCompact) Kit.metrics.touchFloor else 0.dp
    val line = Kit.hairline
    val gap = Kit.space.s

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .defaultMinSize(minWidth = maxOf(Kit.control.keyMinWidth, floor))
            .drawBehind { drawRect(colors.panelBorder, Offset(size.width - line.toPx(), size.height * SEPARATOR_INSET), Size(line.toPx(), size.height * (1 - 2 * SEPARATOR_INSET))) }
            .kitStateLayer(flags, true, colors.plainText)
            .kitFocusRing(flags.focused, RectangleShape)
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
            .padding(horizontal = gap),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(key.label, style = Kit.text.mono.copy(color = colors.plainText), maxLines = 1)
    }
}

/** The share of the row's height left clear above and below a separator, so it reads as a tick, not a wall. */
private const val SEPARATOR_INSET = 0.2f
