package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

/**
 * The accessory key row that sits between the scrollback and the keyboard.
 *
 * Scrolls horizontally rather than wrapping: a second row would eat screen the
 * terminal needs, and the ordering in [TerminalKeyboard] puts the keys worth
 * reaching first on the left.
 */
@Composable
fun TerminalKeyRow(
    onKey: (TerminalKey) -> Unit,
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
        TerminalKeyboard.KEYS.forEach { key ->
            KeyCap(key = key, onClick = { onKey(key) })
        }
    }
}

/**
 * A raised, rounded key. Pressing shifts it to the overlay tone and ticks the
 * keyboard haptic, so a tap registers the way a soft-keyboard key does even
 * when the shell prints nothing back.
 */
@Composable
private fun KeyCap(key: TerminalKey, onClick: () -> Unit) {
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
            .clickable(interactionSource = interaction, indication = ripple()) {
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
