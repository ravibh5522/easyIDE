package dev.easyide.app.ui.screens.workspace.find

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.ui.screens.workspace.edit.SearchResult
import dev.easyide.app.ui.theme.EasyIdeFonts
import dev.easyide.app.ui.theme.Radius
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.Stroke
import dev.easyide.app.ui.theme.editorColors

/**
 * The find/replace bar above the editor. Every control is a full 48dp touch target; below
 * [WIDE_LAYOUT] the option toggles wrap onto their own row instead of squeezing the field.
 *
 * Keyboard: Enter / Shift+Enter step to the next / previous match, Escape closes and hands
 * focus back to the editor. Ctrl+Z inside the field is the field's own undo, not the document's
 * ([onFieldFocusChanged] tells the screen when to leave those chords alone).
 */
@Composable
fun FindBar(
    find: FindController,
    onFieldFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = editorColors
    // The bar leaving composition takes its field's focus with it; the screen must stop treating it as focused.
    DisposableEffect(Unit) { onDispose { onFieldFocusChanged(false) } }
    BoxWithConstraints(modifier = modifier.fillMaxWidth().background(colors.panel)) {
        val wide = maxWidth >= WIDE_LAYOUT
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconAction(
                    icon = if (find.showReplace) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    description = stringResource(R.string.find_toggle_replace),
                    onClick = find::toggleReplace,
                )
                FindField(find, onFieldFocusChanged, modifier = Modifier.weight(1f))
                if (wide) Toggles(find)
                IconAction(Icons.Filled.KeyboardArrowUp, stringResource(R.string.find_previous), find::previous)
                IconAction(Icons.Filled.KeyboardArrowDown, stringResource(R.string.find_next), find::next)
                IconAction(Icons.Filled.Close, stringResource(R.string.find_close), find::close)
            }
            if (!wide) Row(verticalAlignment = Alignment.CenterVertically) { Toggles(find) }
            if (find.showReplace) ReplaceRow(find)
        }
        Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(Stroke.hairline).background(colors.panelBorder))
    }
}

@Composable
private fun FindField(find: FindController, onFocusChanged: (Boolean) -> Unit, modifier: Modifier) {
    val colors = editorColors
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(find.query)) }
    // Opening (again) puts the caret in the field with the query selected, ready to retype.
    LaunchedEffect(find.openRequests) {
        field = TextFieldValue(find.query, TextRange(0, find.query.length))
        focus.requestFocus()
    }
    val failed = find.result is SearchResult.InvalidPattern || find.result is SearchResult.TimedOut

    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .background(colors.background, RoundedCornerShape(Radius.s))
            .border(Stroke.hairline, if (failed) colors.error else colors.panelBorder, RoundedCornerShape(Radius.s))
            .padding(horizontal = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (field.text.isEmpty()) {
                Text(stringResource(R.string.find_hint), style = MaterialTheme.typography.bodyMedium, color = colors.gutterText)
            }
            BasicTextField(
                value = field,
                onValueChange = { next ->
                    field = next
                    if (next.text != find.query) find.onQueryChange(next.text)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.plainText, fontFamily = EasyIdeFonts.mono),
                cursorBrush = SolidColor(colors.cursor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { find.next() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged { onFocusChanged(it.isFocused) }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> { if (event.isShiftPressed) find.previous() else find.next(); true }
                            Key.Escape -> { find.close(); true }
                            else -> false
                        }
                    },
            )
        }
        Text(
            text = summary(find),
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) colors.error else colors.textMuted,
            modifier = Modifier.padding(start = Spacing.s),
        )
    }
}

@Composable
private fun summary(find: FindController): String {
    if (find.query.isEmpty()) return ""
    find.replacedCount?.let { return stringResource(R.string.find_replaced, it) }
    return when (val result = find.result) {
        is SearchResult.InvalidPattern -> stringResource(R.string.find_invalid_pattern)
        SearchResult.TimedOut -> stringResource(R.string.find_timed_out)
        is SearchResult.Found -> when {
            result.matches.isEmpty() -> stringResource(R.string.find_no_results)
            result.truncated -> stringResource(R.string.find_count_capped, find.current + 1, result.matches.size)
            else -> stringResource(R.string.find_count, find.current + 1, result.matches.size)
        }
    }
}

@Composable
private fun Toggles(find: FindController) {
    OptionToggle(stringResource(R.string.find_toggle_case_label), stringResource(R.string.find_toggle_case), find.caseSensitive, find::toggleCase)
    OptionToggle(stringResource(R.string.find_toggle_word_label), stringResource(R.string.find_toggle_word), find.wholeWord, find::toggleWholeWord)
    OptionToggle(stringResource(R.string.find_toggle_regex_label), stringResource(R.string.find_toggle_regex), find.regex, find::toggleRegex)
}

@Composable
private fun ReplaceRow(find: FindController) {
    val colors = editorColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(MIN_TOUCH))
        Box(
            modifier = Modifier
                .weight(1f)
                .minimumInteractiveComponentSize()
                .background(colors.background, RoundedCornerShape(Radius.s))
                .border(Stroke.hairline, colors.panelBorder, RoundedCornerShape(Radius.s))
                .padding(horizontal = Spacing.m),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (find.replacement.isEmpty()) {
                Text(stringResource(R.string.find_replace_hint), style = MaterialTheme.typography.bodyMedium, color = colors.gutterText)
            }
            BasicTextField(
                value = find.replacement,
                onValueChange = find::onReplacementChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.plainText, fontFamily = EasyIdeFonts.mono),
                cursorBrush = SolidColor(colors.cursor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { find.replaceCurrent() }),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { find.close(); true } else false
                },
            )
        }
        TextAction(stringResource(R.string.find_replace_one), find::replaceCurrent)
        TextAction(stringResource(R.string.find_replace_all), find::replaceAll)
    }
}

@Composable
private fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(MIN_TOUCH).clickable(onClick = onClick).semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = editorColors.textMuted)
    }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = Spacing.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = editorColors.accent)
    }
}

/** A find option: a 48dp target showing its glyph, accent-filled while on, announced as a toggle. */
@Composable
private fun OptionToggle(label: String, description: String, checked: Boolean, onClick: () -> Unit) {
    val colors = editorColors
    val fill: Color = if (checked) colors.accent else Color.Transparent
    Box(
        modifier = Modifier
            .size(MIN_TOUCH)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Switch
                selected = checked
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = TOGGLE_CHIP)
                .heightIn(min = TOGGLE_CHIP)
                .background(fill, RoundedCornerShape(Radius.xs)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (checked) colors.onAccent else colors.textMuted,
            )
        }
    }
}

/** Width from which the option toggles share the field's row. */
private val WIDE_LAYOUT = 600.dp
private val MIN_TOUCH = 48.dp
private val TOGGLE_CHIP = 32.dp
