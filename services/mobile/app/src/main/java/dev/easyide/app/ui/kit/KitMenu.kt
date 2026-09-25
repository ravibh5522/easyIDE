package dev.easyide.app.ui.kit

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** One entry of a [KitMenu]. A menu lists actions on an object; a value chooser is a sheet, not a menu (U-CMP-07). */
sealed interface KitMenuItem {
    /** [checked] non-null marks a toggle entry (a tick when on). [hint] is a right-aligned muted suffix such as a shortcut. */
    data class Action(
        val label: String,
        val onClick: () -> Unit,
        val icon: ImageVector? = null,
        val checked: Boolean? = null,
        val enabled: Boolean = true,
        val danger: Boolean = false,
        val hint: String? = null,
    ) : KitMenuItem

    /** An entry that opens [items] beside it; disabled when it has nothing to offer. */
    data class Submenu(
        val label: String,
        val items: List<KitMenuItem>,
        val icon: ImageVector? = null,
        val enabled: Boolean = items.isNotEmpty(),
    ) : KitMenuItem

    data object Divider : KitMenuItem
}

/** Under the parent by default; under [anchor] or the point [at] (window coordinates, see [PressPoint]) when the menu belongs to a press. */
private class MenuPlacement(private val anchor: IntRect?) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        menuPosition(anchor ?: anchorBounds, windowSize, popupContentSize)
}

/** A submenu sits beside the row that opened it; its anchor is that row, its parent panel the row's container. */
internal class SubmenuPlacement(private val parentPanel: () -> IntRect) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        submenuPosition(anchorBounds, parentPanel(), windowSize, popupContentSize)
}

/**
 * The VS Code menu: rows at the row token, a hover and focus fill, shortcut hints, check marks, dividers,
 * submenus that open beside their row (below on a narrow window), arrow-key navigation (Up, Down, Home, End,
 * Right into a submenu, Left or Escape out of it, Enter to run) and placement clamped inside the window.
 *
 * Place it inside the anchor's parent [Box], as with any popup: it opens under that parent, or under
 * [anchor] (window coordinates) or the point [at] when the menu answers a press. Back and outside taps dismiss.
 */
@Composable
fun KitMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<KitMenuItem>,
    modifier: Modifier = Modifier,
    at: IntOffset? = null,
    anchor: IntRect? = null,
) {
    if (!expanded) return
    val root = remember(items) { tidyMenu(items) }
    val target = anchor ?: at?.let { IntRect(it, IntSize.Zero) }
    var nav by remember { mutableStateOf(MenuNav()) }
    val focus = remember { FocusRequester() }
    val run = { item: KitMenuItem.Action -> item.onClick(); onDismiss() }

    Popup(remember(target) { MenuPlacement(target) }, onDismiss, PopupProperties(focusable = true)) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        val keys = Modifier.focusRequester(focus).focusable().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionDown -> nav = nav.move(root, 1)
                Key.DirectionUp -> nav = nav.move(root, -1)
                Key.MoveHome -> nav = nav.edge(root, last = false)
                Key.MoveEnd -> nav = nav.edge(root, last = true)
                Key.DirectionRight -> nav = nav.open(root)
                Key.DirectionLeft -> nav = nav.close()
                Key.Escape -> if (nav.path.size > 1) nav = nav.close() else onDismiss()
                Key.Enter, Key.NumPadEnter, Key.Spacebar -> when (val item = nav.focused(root)) {
                    is KitMenuItem.Action -> run(item)
                    is KitMenuItem.Submenu -> nav = nav.open(root)
                    else -> Unit
                }
                else -> return@onPreviewKeyEvent false
            }
            true
        }
        KitMenuPanel(
            items = root, level = 0, nav = nav, root = root,
            onNav = { nav = it }, onRun = run,
            modifier = modifier.then(keys),
        )
    }
}
