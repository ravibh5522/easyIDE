package dev.easyide.app.ui.screens.workspace.find

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.kit.collectFlags
import dev.easyide.app.ui.kit.kitFocusRing
import dev.easyide.app.ui.kit.kitStateLayer
import dev.easyide.app.ui.screens.workspace.edit.SearchResult

/** The bordered frame both find inputs sit in: the editor's tone, a hairline that turns error-coloured on a bad pattern and accent-coloured with focus. */
@Composable
private fun InputFrame(modifier: Modifier, failed: Boolean = false, focused: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val colors = Kit.colors
    val shape = RoundedCornerShape(Kit.radius.xs)
    val line = when {
        failed -> colors.error
        focused -> colors.focus
        else -> colors.panelBorder
    }
    Row(
        modifier = modifier
            .heightIn(min = Kit.control.fieldHeight)
            .background(colors.background, shape)
            .border(Kit.hairline, line, shape)
            .padding(start = Kit.space.s, end = Kit.space.xs),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The find field; on a [wide] card the match counter and the option toggles sit inside its frame, else they share the row below it. */
@Composable
internal fun FindField(find: FindController, onFocusChanged: (Boolean) -> Unit, wide: Boolean, modifier: Modifier) {
    val colors = Kit.colors
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(find.query)) }
    var focused by remember { mutableStateOf(false) }
    // Opening (again) puts the caret in the field with the query selected, ready to retype.
    LaunchedEffect(find.openRequests) {
        field = TextFieldValue(find.query, TextRange(0, find.query.length))
        focus.requestFocus()
    }
    val failed = find.result is SearchResult.InvalidPattern || find.result is SearchResult.TimedOut
    val text = Kit.text.body.copy(color = colors.plainText)

    InputFrame(modifier, failed, focused) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (field.text.isEmpty()) BasicText(stringResource(R.string.find_hint), style = text.copy(color = colors.textMuted), maxLines = 1)
            BasicTextField(
                value = field,
                onValueChange = { next ->
                    field = next
                    if (next.text != find.query) find.onQueryChange(next.text)
                },
                singleLine = true,
                textStyle = text,
                cursorBrush = SolidColor(colors.cursor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { find.next() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused; onFocusChanged(it.isFocused) }
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
        if (wide) {
            FindCounter(find)
            Row(horizontalArrangement = Arrangement.spacedBy(Kit.space.xxs)) { FindToggles(find) }
        }
    }
}

/** `3 of 12`, or why there is nothing to count; the error colour for a pattern that cannot run. */
@Composable
internal fun FindCounter(find: FindController) {
    val failed = find.result is SearchResult.InvalidPattern || find.result is SearchResult.TimedOut
    BasicText(
        text = summary(find),
        style = Kit.text.caption.copy(color = if (failed) Kit.colors.error else Kit.colors.textMuted),
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = Modifier.padding(horizontal = Kit.space.xs),
    )
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
internal fun FindToggles(find: FindController) {
    FlatToggle(stringResource(R.string.find_toggle_case_label), stringResource(R.string.find_toggle_case), find.caseSensitive, find::toggleCase)
    FlatToggle(stringResource(R.string.find_toggle_word_label), stringResource(R.string.find_toggle_word), find.wholeWord, find::toggleWholeWord)
    FlatToggle(stringResource(R.string.find_toggle_regex_label), stringResource(R.string.find_toggle_regex), find.regex, find::toggleRegex)
}

/** The replace field; with [actions] its two buttons follow it on the row (a wide card), else [ReplaceActions] sits with the toggles. */
@Composable
internal fun ReplaceRow(find: FindController, actions: Boolean) {
    val colors = Kit.colors
    val text = Kit.text.body.copy(color = colors.plainText)
    var focused by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
        InputFrame(Modifier.weight(1f), focused = focused) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (find.replacement.isEmpty()) BasicText(stringResource(R.string.find_replace_hint), style = text.copy(color = colors.textMuted), maxLines = 1)
                BasicTextField(
                    value = find.replacement,
                    onValueChange = find::onReplacementChange,
                    singleLine = true,
                    textStyle = text,
                    cursorBrush = SolidColor(colors.cursor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { find.replaceCurrent() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { find.close(); true } else false
                    },
                )
            }
        }
        if (actions) ReplaceActions(find)
    }
}

@Composable
internal fun ReplaceActions(find: FindController) {
    FlatText(stringResource(R.string.find_replace_one), stringResource(R.string.find_replace_one), find::replaceCurrent)
    FlatText(stringResource(R.string.find_replace_all_short), stringResource(R.string.find_replace_all), find::replaceAll)
}

/** A find option: a flat square the size of a toolbar glyph, washed with the accent while on, named by its full description. */
@Composable
private fun FlatToggle(label: String, description: String, checked: Boolean, onClick: () -> Unit) {
    val colors = Kit.colors
    val interaction = remember { MutableInteractionSource() }
    val flags = interaction.collectFlags()
    val shape = RoundedCornerShape(Kit.radius.xs)
    Box(
        Modifier.defaultMinSize(TOGGLE_SIZE, TOGGLE_SIZE)
            .clip(shape)
            .background(if (checked) Tone.Accent.container(colors) else Color.Transparent)
            .kitStateLayer(flags, true, colors.plainText)
            .kitFocusRing(flags.focused, shape)
            .clickable(interaction, null, role = Role.Checkbox, onClick = onClick)
            .semantics { contentDescription = description; selected = checked }
            .padding(horizontal = Kit.space.xxs),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = Kit.text.monoSmall.copy(color = if (checked) colors.plainText else colors.textMuted), maxLines = 1)
    }
}

/** A flat text action of the replace row, named [description] for a screen reader. */
@Composable
private fun FlatText(label: String, description: String, onClick: () -> Unit) {
    val colors = Kit.colors
    val interaction = remember { MutableInteractionSource() }
    val flags = interaction.collectFlags()
    val shape = RoundedCornerShape(Kit.radius.xs)
    Box(
        Modifier.heightIn(min = TOGGLE_SIZE).clip(shape)
            .kitStateLayer(flags, true, colors.plainText)
            .kitFocusRing(flags.focused, shape)
            .clickable(interaction, null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = Kit.space.s),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = Kit.text.body.copy(color = colors.plainText), maxLines = 1)
    }
}

/** VS Code's option toggles are 22 square. */
private val TOGGLE_SIZE = 22.dp
