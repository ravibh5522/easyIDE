package dev.easyide.app.ui.screens.workspace.find

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.ui.icons.iconFor
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.kitTag

/**
 * The find/replace widget over the editor (VS Code's): a card at the editor's top right, [CARD_MAX_WIDTH] at most, a
 * hairline border and a small radius, no shadow. The chevron on its left shows the replace row; the option
 * toggles sit inside the find field on a wide card; under 380dp they take a row of their own, with the replace buttons. On a compact
 * window the card spans the editor, without margin or radius.
 *
 * Keyboard: Enter / Shift+Enter step to the next / previous match, Escape closes and hands
 * focus back to the editor. Ctrl+Z inside the field is the field's own undo, not the document's
 * ([onFieldFocusChanged] tells the screen when to leave those chords alone).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FindBar(
    find: FindController,
    onFieldFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = Kit.colors
    // The bar leaving composition takes its field's focus with it; the screen must stop treating it as focused.
    DisposableEffect(Unit) { onDispose { onFieldFocusChanged(false) } }
    val shape = RoundedCornerShape(if (compact) 0.dp else Kit.radius.xs)
    val frame = if (compact) modifier.fillMaxWidth() else modifier.widthIn(max = CARD_MAX_WIDTH).padding(end = Kit.space.l)
    BoxWithConstraints(
        frame.kitTag("find-widget").background(colors.overlay, shape).border(Kit.hairline, colors.panelBorder, shape)
            // The card is opaque to touches: a tap on it must not reach the text beneath.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        val wide = maxWidth >= WIDE_LAYOUT
        Row(Modifier.padding(Kit.space.xs), verticalAlignment = Alignment.Top) {
            KitIconButton(
                icon = iconFor(if (find.showReplace) "chevron_down" else "chevron_right"),
                contentDescription = stringResource(R.string.find_toggle_replace),
                onClick = find::toggleReplace,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FindField(find, onFieldFocusChanged, wide, Modifier.weight(1f))
                    KitIconButton(iconFor("chevron_up"), stringResource(R.string.find_previous), find::previous)
                    KitIconButton(iconFor("chevron_down"), stringResource(R.string.find_next), find::next)
                    KitIconButton(iconFor("close"), stringResource(R.string.find_close), find::close)
                }
                if (!wide) FlowRow(Modifier.padding(start = Kit.space.xs), verticalArrangement = Arrangement.spacedBy(Kit.space.xs), horizontalArrangement = Arrangement.spacedBy(Kit.space.xs), itemVerticalAlignment = Alignment.CenterVertically) {
                    FindCounter(find)
                    FindToggles(find)
                    if (find.showReplace) ReplaceActions(find)
                }
                if (find.showReplace) ReplaceRow(find, actions = wide)
            }
        }
    }
}

/** The bar's width from which the option toggles share the field's row. */
private val WIDE_LAYOUT = 380.dp

/** VS Code's widget is 420 wide. */
private val CARD_MAX_WIDTH = 420.dp
