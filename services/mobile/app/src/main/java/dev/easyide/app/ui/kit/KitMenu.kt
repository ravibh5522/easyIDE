package dev.easyide.app.ui.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.easyide.app.ui.icons.iconFor
import dev.easyide.app.ui.theme.IconSize

/** One entry of a [KitMenu]. A menu lists actions on an object; a value chooser is a sheet, not a menu (U-CMP-07). */
sealed interface KitMenuItem {
    /** [checked] non-null marks a toggle entry (a tick when on). [hint] is a mono suffix such as a shortcut. */
    data class Action(
        val label: String,
        val onClick: () -> Unit,
        val icon: ImageVector? = null,
        val checked: Boolean? = null,
        val enabled: Boolean = true,
        val danger: Boolean = false,
        val hint: String? = null,
    ) : KitMenuItem

    data object Divider : KitMenuItem
}

/** Dividers only separate: none first, last or doubled. Extension-fed menus can produce any of those. */
internal fun tidyMenu(items: List<KitMenuItem>): List<KitMenuItem> {
    val out = ArrayList<KitMenuItem>(items.size)
    for (item in items) {
        if (item !is KitMenuItem.Divider || (out.isNotEmpty() && out.last() !is KitMenuItem.Divider)) out += item
    }
    if (out.lastOrNull() is KitMenuItem.Divider) out.removeAt(out.lastIndex)
    return out
}

/** Below the anchor, start-aligned; flipped above when it would run off the bottom; clamped inside the window. */
internal fun menuPosition(anchor: IntRect, window: IntSize, popup: IntSize): IntOffset {
    val below = anchor.bottom
    val y = if (below + popup.height <= window.height) below else maxOf(anchor.top - popup.height, 0)
    val x = anchor.left.coerceIn(0, maxOf(window.width - popup.width, 0))
    return IntOffset(x, y)
}

private object MenuPlacement : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        menuPosition(anchorBounds, windowSize, popupContentSize)
}

/** Place it inside the anchor's parent [Box], as with any popup: it opens under that parent. Back and outside taps dismiss. */
@Composable
fun KitMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<KitMenuItem>, modifier: Modifier = Modifier) {
    if (!expanded) return
    val colors = Kit.colors
    val shape = RoundedCornerShape(Kit.radius.m)
    Popup(MenuPlacement, onDismiss, PopupProperties(focusable = true)) {
        Column(
            modifier = modifier.kitTag("menu")
                .width(IntrinsicSize.Max)
                .widthIn(min = KitSizes.menuMinWidth, max = KitSizes.menuMaxWidth)
                .clip(shape)
                .background(colors.overlay)
                .border(Kit.hairline, colors.panelBorder, shape)
                .verticalScroll(rememberScrollState())
                .padding(vertical = Kit.space.xs),
        ) {
            tidyMenu(items).forEach { item ->
                when (item) {
                    is KitMenuItem.Action -> MenuRow(item) { item.onClick(); onDismiss() }
                    KitMenuItem.Divider -> Spacer(Modifier.fillMaxWidth().padding(vertical = Kit.space.xs).height(Kit.hairline).background(colors.panelBorder))
                }
            }
        }
    }
}

@Composable
private fun MenuRow(item: KitMenuItem.Action, onClick: () -> Unit) {
    val colors = Kit.colors
    val tint = when {
        !item.enabled -> colors.textDisabled
        item.danger -> Tone.Danger.content(colors)
        else -> colors.plainText
    }
    val interaction = remember { MutableInteractionSource() }
    val flags = interaction.collectFlags()
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = Kit.metrics.touchFloor)
            .clickable(interaction, null, enabled = item.enabled, role = Role.Button, onClick = onClick)
            .semantics { item.checked?.let { toggleableState = ToggleableState(it) } }
            .kitStateLayer(flags, item.enabled, tint)
            .kitFocusRing(flags.focused, RoundedCornerShape(Kit.radius.xs))
            .padding(horizontal = Kit.space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Kit.space.s),
    ) {
        item.icon?.let { Image(it, null, Modifier.size(IconSize.l), colorFilter = ColorFilter.tint(tint)) }
        BasicText(item.label, Modifier.weight(1f), style = Kit.type.bodyMedium.copy(color = tint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.hint?.let { BasicText(it, style = Kit.type.labelSmall.kitMono().copy(color = colors.textMuted)) }
        if (item.checked == true) Image(iconFor("check"), null, Modifier.size(IconSize.l), colorFilter = ColorFilter.tint(colors.accent))
    }
}
