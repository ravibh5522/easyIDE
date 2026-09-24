package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.easyide.app.ui.theme.ControlSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.Stroke
import dev.easyide.app.ui.theme.editorColors
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
    val colors = editorColors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.panel)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.forEach { key ->
            KeyCap(key = key, onClick = { onKey(key.action) }, onLongClick = key.longPress?.let { lp -> { onKey(lp) } })
        }
    }
}

/**
 * A raised, rounded key; a long press runs the key's `longPress` action. Pressing shifts it to the overlay tone and ticks the
 * keyboard haptic, so a tap registers the way a soft-keyboard key does even
 * when the shell prints nothing back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyCap(key: RowKey, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val colors = editorColors
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = ControlSize.keyMinWidth, minHeight = ControlSize.keyHeight)
            .clip(MaterialTheme.shapes.small)
            .background(if (pressed) colors.overlay else colors.raised)
            .border(Stroke.hairline, colors.panelBorder, MaterialTheme.shapes.small)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                onLongClick = onLongClick?.let { lp -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); lp() } },
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                onClick()
            }
            .padding(horizontal = Spacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            style = codeTextStyle().copy(color = colors.plainText),
        )
    }
}
