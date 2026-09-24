package dev.easyide.app.ui.commands

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.Stroke
import dev.easyide.app.ui.theme.editorColors

/**
 * The shared shell of every "type to filter, arrows and Enter to pick" overlay: the command
 * palette, quick open and go to line. A scrim, a panel near the top, a text field that takes
 * focus, and a list whose rows are at least a finger tall. The caller owns the query and the
 * filtering; this owns focus, keyboard navigation (arrows, Enter, Escape/Back) and the tap
 * behaviour, so the three overlays cannot drift apart.
 *
 * Enter from either a hardware or a soft keyboard picks the highlighted row. Tapping the scrim
 * dismisses; taps on the panel itself do not.
 */
@Composable
fun <T> PickerOverlay(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    hint: String,
    items: List<T>,
    itemKey: (T) -> Any,
    onChoose: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** Shown between the field and the list, e.g. "No matching commands" or an indexing note. */
    banner: (@Composable () -> Unit)? = null,
    row: @Composable (item: T, highlighted: Boolean) -> Unit,
) {
    val colors = editorColors
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // Back at the first row whenever the query changes: the best match is the one Enter takes.
    var selected by remember(value.text) { mutableIntStateOf(0) }
    val current = selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))

    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(current) { if (items.isNotEmpty()) listState.animateScrollToItem(current) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
            .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(top = TOP_OFFSET, start = Spacing.l, end = Spacing.l)
                .widthIn(max = MAX_WIDTH)
                .fillMaxWidth()
                .background(colors.panel)
                .border(Stroke.hairline, colors.panelBorder)
                // Swallow taps on the panel itself so only the scrim dismisses.
                .clickable(interactionSource = null, indication = null, onClick = {}),
        ) {
            Box(modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().padding(Spacing.m), contentAlignment = Alignment.CenterStart) {
                if (value.text.isEmpty()) {
                    Text(text = hint, style = Kit.text.body, color = colors.gutterText)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = Kit.text.body.copy(color = colors.plainText),
                    cursorBrush = SolidColor(colors.plainText),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { items.getOrNull(current)?.let(onChoose) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> { selected = (current + 1).coerceAtMost(items.lastIndex); true }
                                Key.DirectionUp -> { selected = (current - 1).coerceAtLeast(0); true }
                                Key.Enter, Key.NumPadEnter -> { items.getOrNull(current)?.let(onChoose); true }
                                Key.Escape -> { onDismiss(); true }
                                else -> false
                            }
                        },
                )
            }

            banner?.invoke()

            LazyColumn(state = listState, modifier = Modifier.heightIn(max = MAX_LIST_HEIGHT)) {
                itemsIndexed(items, key = { _, item -> itemKey(item) }) { index, item ->
                    val highlighted = index == current
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (highlighted) colors.panelBorder else colors.panel)
                            .clickable { onChoose(item) }
                            .minimumInteractiveComponentSize()
                            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                        contentAlignment = Alignment.CenterStart,
                    ) { row(item, highlighted) }
                }
            }
        }
    }
}

private const val SCRIM_ALPHA = 0.4f
private val TOP_OFFSET = Spacing.xxxl + Spacing.s
private val MAX_WIDTH = 560.dp
private val MAX_LIST_HEIGHT = 360.dp
