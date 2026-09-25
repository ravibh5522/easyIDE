package dev.easyide.app.ui.commands

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.theme.Spacing
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
    /** Text the field starts with (quick open hands over what followed its `>`). */
    initialQuery: String = "",
    /**
     * Quick-open prefixes (`@` document symbols, `#` workspace symbols): called with the
     * prefix and the rest of the query; true hands the query over and closes the palette.
     */
    onPrefix: (prefix: Char, query: String) -> Boolean = { _, _ -> false },
) {
    val colors = editorColors
    var field by remember { mutableStateOf(TextFieldValue(initialQuery, TextRange(initialQuery.length))) }

    val entries = registry.commands
        .filter { it.id != CommandIds.SHOW_COMMANDS }
        .map { command ->
            val title = command.title.text()
            PaletteEntry(command, command.category?.let { "$it: $title" } ?: title, keymap.labelFor(command.id))
        }
    val matches = fuzzyFilter(field.text, entries) { it.title }

    // Dismiss first: the command may itself open UI that expects the palette gone.
    fun runEntry(entry: PaletteEntry) {
        if (!entry.command.enabled) return
        onDismiss()
        entry.command.run()
    }

    PickerOverlay(
        value = field,
        onValueChange = { next ->
            val text = next.text
            if (text.isNotEmpty() && onPrefix(text[0], text.substring(1))) onDismiss() else field = next
        },
        hint = stringResource(R.string.palette_hint),
        items = matches,
        itemKey = { it.command.id },
        onChoose = ::runEntry,
        onDismiss = onDismiss,
        modifier = modifier,
        banner = if (matches.isEmpty()) {
            {
                Text(
                    text = stringResource(R.string.palette_empty),
                    style = Kit.text.caption,
                    color = colors.gutterText,
                    modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
                )
            }
        } else null,
    ) { entry, _ ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.title,
                style = Kit.text.body,
                color = if (entry.command.enabled) colors.plainText else colors.gutterText,
                modifier = Modifier.weight(1f),
            )
            entry.chord?.let {
                Text(text = it, style = Kit.text.label, color = colors.gutterText)
            }
        }
    }
}
