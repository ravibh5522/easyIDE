package dev.easyide.app.ui.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.easyide.app.ui.icons.iconFor

/** Rows with an icon or a check share one glyph column; a menu with neither has no column, so its labels start at the row's edge. */
internal fun hasGlyph(item: KitMenuItem): Boolean = when (item) {
    is KitMenuItem.Action -> item.icon != null || item.checked != null
    is KitMenuItem.Submenu -> item.icon != null
    KitMenuItem.Divider -> false
}

/** Whether the row at [index] of this level is the one focus or hover is on, and whether its submenu is showing. */
private fun MenuNav.isFocused(level: Int, index: Int) = path.getOrNull(level) == index
private fun MenuNav.opensBelow(level: Int, index: Int) = path.size > level + 1 && path[level] == index

/**
 * One level of a menu: the rounded, bordered surface and its rows. [nav] says which row is focused;
 * pointer hover and taps report back through [onNav] and [onRun], so keyboard and pointer share one focus.
 * A submenu opens as a popup beside its row; [KitMenuCascade] draws them inline for goldens.
 */
@Composable
internal fun KitMenuPanel(
    items: List<KitMenuItem>,
    level: Int,
    nav: MenuNav,
    root: List<KitMenuItem>,
    onNav: (MenuNav) -> Unit,
    onRun: (KitMenuItem.Action) -> Unit,
    modifier: Modifier = Modifier,
    inlineSubmenus: Boolean = false,
) {
    val colors = Kit.colors
    val shape = RoundedCornerShape(Kit.radius.s)
    var bounds by remember { mutableStateOf(IntRect.Zero) }
    val slot = items.any { hasGlyph(it) }

    Column(
        modifier = modifier.kitTag("menu")
            .onGloballyPositioned { bounds = it.boundsInWindow().let { r -> IntRect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()) } }
            .width(IntrinsicSize.Max)
            .widthIn(min = KitSizes.menuMinWidth, max = KitSizes.menuMaxWidth)
            .heightIn(max = KitSizes.menuMaxHeight)
            .clip(shape)
            .background(colors.overlay)
            .border(Kit.hairline, colors.panelBorder, shape)
            .verticalScroll(rememberScrollState())
            .padding(vertical = Kit.space.xs),
    ) {
        items.forEachIndexed { index, item ->
            when (item) {
                KitMenuItem.Divider -> Spacer(Modifier.fillMaxWidth().padding(vertical = Kit.space.xs).height(Kit.hairline).background(colors.panelBorder))
                is KitMenuItem.Action -> MenuRow(
                    label = item.label, icon = item.icon, hint = item.hint, checked = item.checked, submenu = false,
                    enabled = item.enabled, danger = item.danger, focused = nav.isFocused(level, index), slot = slot,
                    onHover = { onNav(nav.hover(root, level, index)) }, onClick = { onRun(item) },
                )
                is KitMenuItem.Submenu -> Box {
                    MenuRow(
                        label = item.label, icon = item.icon, hint = null, checked = null, submenu = true,
                        enabled = item.enabled, danger = false, focused = nav.isFocused(level, index), slot = slot,
                        onHover = { onNav(nav.hover(root, level, index)) }, onClick = { onNav(nav.hover(root, level, index)) },
                    )
                    if (nav.opensBelow(level, index) && !inlineSubmenus) {
                        Popup(remember(bounds) { SubmenuPlacement { bounds } }, properties = PopupProperties(focusable = false)) {
                            KitMenuPanel(item.items, level + 1, nav, root, onNav, onRun)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    label: String,
    icon: ImageVector?,
    hint: String?,
    checked: Boolean?,
    submenu: Boolean,
    enabled: Boolean,
    danger: Boolean,
    focused: Boolean,
    slot: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = Kit.colors
    val fill = focused && enabled
    val tint: Color = when {
        !enabled -> colors.textDisabled
        danger -> Tone.Danger.content(colors)
        fill -> colors.listSelectionText
        else -> colors.plainText
    }
    val quiet = if (fill && !danger) colors.listSelectionText else colors.textMuted
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val flags = interaction.collectFlags()
    LaunchedEffect(hovered) { if (hovered && enabled) onHover() }
    val shape = RoundedCornerShape(Kit.radius.xs)
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = Kit.space.xs)
            .heightIn(min = Kit.control.rowHeight)
            .clip(shape)
            .then(if (fill) Modifier.background(colors.listSelection) else Modifier)
            .hoverable(interaction, enabled)
            .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { checked?.let { toggleableState = ToggleableState(it) } }
            .kitStateLayer(KitFlags(flags.pressed, false, false), enabled, tint)
            .padding(horizontal = Kit.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Kit.space.s),
    ) {
        if (slot) {
            val glyph = icon ?: if (checked == true) iconFor("check") else null
            Box(Modifier.size(Kit.control.rowIcon), Alignment.Center) {
                glyph?.let { Image(it, null, Modifier.size(Kit.control.rowIcon), colorFilter = ColorFilter.tint(tint)) }
            }
        }
        BasicText(label, Modifier.weight(1f), style = Kit.text.body.copy(color = tint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        hint?.let { BasicText(it, style = Kit.text.caption.copy(color = if (enabled) quiet else colors.textDisabled)) }
        if (submenu) Image(iconFor("chevron_right"), null, Modifier.size(Kit.control.rowIcon), colorFilter = ColorFilter.tint(tint))
    }
}

/** A menu with its open submenus laid out side by side in one composition, for goldens and the gallery, where a popup window cannot be captured. */
@Composable
internal fun KitMenuCascade(items: List<KitMenuItem>, nav: MenuNav, modifier: Modifier = Modifier) {
    val root = remember(items) { tidyMenu(items) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Kit.space.xxs)) {
        for (level in 0 until maxOf(nav.path.size, 1)) {
            val levelItems = nav.itemsAt(root, level)
            val parentItems = if (level == 0) emptyList() else nav.itemsAt(root, level - 1).take(nav.path[level - 1])
            val above = parentItems.fold(Kit.space.xs) { y, item ->
                y + if (item is KitMenuItem.Divider) Kit.space.xs * 2 + Kit.hairline else Kit.control.rowHeight
            }
            Column {
                Spacer(Modifier.height(above))
                KitMenuPanel(levelItems, level, nav, root, {}, {}, inlineSubmenus = true)
            }
        }
    }
}
