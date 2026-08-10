package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
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
            .padding(horizontal = ROW_PADDING_DP.dp, vertical = ROW_PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalKeyboard.KEYS.forEach { key ->
            KeyCap(key = key, onClick = { onKey(key) })
        }
    }
}

@Composable
private fun KeyCap(key: TerminalKey, onClick: () -> Unit) {
    val colors = editorColors

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = KEY_MIN_WIDTH_DP.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(colors.tabInactive)
            .clickable(onClick = onClick)
            .padding(horizontal = KEY_PADDING_H_DP.dp, vertical = KEY_PADDING_V_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            style = codeTextStyle().copy(color = colors.plainText),
        )
    }
}

private const val ROW_PADDING_DP = 4
private const val KEY_GAP_DP = 4
private const val KEY_MIN_WIDTH_DP = 36
private const val KEY_PADDING_H_DP = 8
private const val KEY_PADDING_V_DP = 8
