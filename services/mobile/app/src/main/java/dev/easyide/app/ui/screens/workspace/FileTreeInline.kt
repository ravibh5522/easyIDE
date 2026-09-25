package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitSizes
import dev.easyide.sandbox.files.FileNode

/** A name being typed into the tree itself: a new file or folder under [parentDir] ("" is the project root), or a rename. */
sealed interface InlineEdit {
    data class NewFile(val parentDir: String) : InlineEdit
    data class NewFolder(val parentDir: String) : InlineEdit
    data class Rename(val node: FileNode) : InlineEdit

    /** The directory whose children the row appears among, or null for a rename, which replaces the row it renames. */
    val parent: String? get() = when (this) {
        is NewFile -> parentDir
        is NewFolder -> parentDir
        is Rename -> null
    }

    val initial: String get() = (this as? Rename)?.node?.name.orEmpty()
}

/** The tree's inline edit and what ends it: [onCommit] with the typed name, or [onCancel]. */
class InlineEditSpec(val edit: InlineEdit?, val onCommit: (InlineEdit, String) -> Unit, val onCancel: () -> Unit) {
    companion object {
        val NONE = InlineEditSpec(null, { _, _ -> }, {})
    }
}

/**
 * The row a name is typed into, at the depth of the rows it sits among (or in place of the row being
 * renamed). Enter commits a changed, non-blank name; Escape or leaving the field cancels, so an abandoned
 * edit never leaves a stray row or a half-made file.
 */
@Composable
internal fun InlineNameRow(edit: InlineEdit, depth: Int, onCommit: (String) -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    var name by remember(edit) { mutableStateOf(edit.initial) }
    var focused by remember(edit) { mutableStateOf(false) }
    val submit = { name.trim().takeIf { it.isNotEmpty() && it != edit.initial }?.let(onCommit) ?: onCancel() }
    LaunchedEffect(edit) { focus.requestFocus() }
    val label = stringResource(
        when (edit) {
            is InlineEdit.NewFile -> R.string.wp_new_file
            is InlineEdit.NewFolder -> R.string.wp_new_folder
            is InlineEdit.Rename -> R.string.wp_rename
        },
    )
    // The field starts where the icon of a row at this depth does: gutter, indent, then the twistie column.
    val start = Kit.control.hPad + Kit.control.indent * depth + KitSizes.twistieSlot + Kit.space.xs
    Row(Modifier.fillMaxWidth().treeGuides(depth).padding(start = start, end = Kit.control.hPad), verticalAlignment = Alignment.CenterVertically) {
        KitField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.weight(1f),
            mono = true,
            keyboard = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            inputModifier = Modifier
                .focusRequester(focus)
                .onFocusChanged { state ->
                    if (state.isFocused) focused = true else if (focused) onCancel()
                }
                .onPreviewKeyEvent { event ->
                    val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                    if (escape) onCancel()
                    escape
                },
            hint = label,
        )
    }
}
