package dev.easyide.app.ui.screens.workspace.find

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import dev.easyide.app.R
import dev.easyide.app.ui.commands.PickerOverlay
import dev.easyide.app.ui.screens.workspace.edit.GoToLine
import dev.easyide.app.ui.screens.workspace.edit.GoToTarget
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors

/**
 * Go to line (Ctrl+G): type `12` or `12:5`, see where that lands, Enter to go. A line past the
 * end lands on the last line; anything that is not a positive number shows why, and Enter does
 * nothing until it is one.
 */
@Composable
fun GoToLineOverlay(lineCount: Int, onGo: (GoToTarget) -> Unit, onDismiss: () -> Unit) {
    val colors = editorColors
    var field by remember { mutableStateOf(TextFieldValue()) }
    val target = GoToLine.parse(field.text, lineCount)

    PickerOverlay(
        value = field,
        onValueChange = { field = it },
        hint = stringResource(R.string.goto_line_hint),
        items = listOfNotNull(target),
        itemKey = { it },
        onChoose = { onDismiss(); onGo(it) },
        onDismiss = onDismiss,
        banner = if (field.text.isNotBlank() && target == null) {
            {
                Text(
                    text = stringResource(R.string.goto_line_invalid, lineCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
                )
            }
        } else null,
    ) { it, _ ->
        Text(
            text = stringResource(R.string.goto_line_preview, it.line + 1, lineCount),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.plainText,
        )
    }
}
