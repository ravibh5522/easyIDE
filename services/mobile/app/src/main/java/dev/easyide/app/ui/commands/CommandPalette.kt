package dev.easyide.app.ui.commands

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.ui.theme.editorColors

private class PaletteEntry(val command: Command, val title: String, val chord: String?)

/**
 * The command palette: every registered command, fuzzy-filtered as you type.
 * Arrow keys move the selection, Enter runs it, Escape / Back / tapping
 * outside closes; rows are also tappable, so it works without a keyboard.
 */
@Composable
fun CommandPalette(
    registry: CommandRegistry,
    keymap: Keymap,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = editorColors
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val entries = registry.commands
        .filter { it.id != CommandIds.SHOW_COMMANDS }
        .map { command ->
            PaletteEntry(command, stringResource(command.title), keymap.chordFor(command.id)?.let(Keymap::label))
        }
    val matches = fuzzyFilter(query, entries) { it.title }
    val current = selected.coerceIn(0, (matches.size - 1).coerceAtLeast(0))

    // Dismiss first: the command may itself open UI that expects the palette gone.
    fun runEntry(entry: PaletteEntry) {
        if (!entry.command.enabled) return
        onDismiss()
        entry.command.run()
    }

    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(current) { if (matches.isNotEmpty()) listState.animateScrollToItem(current) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
            .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(top = TOP_OFFSET_DP.dp, start = 16.dp, end = 16.dp)
                .widthIn(max = MAX_WIDTH_DP.dp)
                .fillMaxWidth()
                .background(colors.panel)
                .border(BORDER_DP.dp, colors.panelBorder)
                // Swallow taps on the panel itself so only the scrim dismisses.
                .clickable(interactionSource = null, indication = null, onClick = {}),
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.palette_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.gutterText,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it; selected = 0 },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.plainText),
                    cursorBrush = SolidColor(colors.plainText),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> { selected = (current + 1).coerceAtMost(matches.lastIndex); true }
                                Key.DirectionUp -> { selected = (current - 1).coerceAtLeast(0); true }
                                Key.Enter, Key.NumPadEnter -> { matches.getOrNull(current)?.let(::runEntry); true }
                                Key.Escape -> { onDismiss(); true }
                                else -> false
                            }
                        },
                )
            }

            if (matches.isEmpty()) {
                Text(
                    text = stringResource(R.string.palette_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.gutterText,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            LazyColumn(state = listState, modifier = Modifier.heightIn(max = MAX_LIST_HEIGHT_DP.dp)) {
                itemsIndexed(matches, key = { _, entry -> entry.command.id }) { index, entry ->
                    PaletteRow(entry, highlighted = index == current, onClick = { runEntry(entry) })
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(entry: PaletteEntry, highlighted: Boolean, onClick: () -> Unit) {
    val colors = editorColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (highlighted) colors.panelBorder else colors.panel)
            .clickable(enabled = entry.command.enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.command.enabled) colors.plainText else colors.gutterText,
            modifier = Modifier.weight(1f),
        )
        entry.chord?.let {
            Text(text = it, style = MaterialTheme.typography.labelSmall, color = colors.gutterText)
        }
    }
}

private const val SCRIM_ALPHA = 0.4f
private const val TOP_OFFSET_DP = 56
private const val MAX_WIDTH_DP = 560
private const val MAX_LIST_HEIGHT_DP = 360
private const val BORDER_DP = 1
