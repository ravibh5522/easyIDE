package dev.easyide.app.ui.shell.host

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import dev.easyide.app.R
import dev.easyide.app.ui.icons.iconFor
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.app.ui.kit.collectFlags
import dev.easyide.app.ui.kit.kitFocusRing
import dev.easyide.app.ui.kit.kitPressPoint
import dev.easyide.app.ui.kit.kitPressable
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.kit.rememberPressPoint
import dev.easyide.app.ui.shell.EditorGroup
import dev.easyide.app.ui.shell.Tab
import dev.easyide.app.ui.shell.TabState

/**
 * The tab strip of one editor group on a wide window, in VS Code's structure: a scrolling row of flat
 * tabs split by hairlines (icon, name, and a dot or cross), the active one in the editor's tone with an
 * accent line on top and kept in view, the others a shade darker; then, fixed at the end, the list of
 * all open documents (only while the tabs overflow) and the group's [trailing] actions. The preview tab
 * is italic (the next preview replaces it); a tap activates a tab, a double tap keeps a preview, a long
 * press or right click opens the tab's menu. A dirty document shows a dot in place of its cross, and the
 * close button's name says so.
 */
@Composable
fun DocumentStrip(
    group: EditorGroup,
    titleOf: @Composable (Tab) -> String,
    isDirty: (Tab) -> Boolean,
    onActivate: (Tab) -> Unit,
    onKeep: (Tab) -> Unit,
    onClose: (Tab) -> Unit,
    modifier: Modifier = Modifier,
    /** A long press or right click on a tab, with its window position; null leaves tabs without a menu. */
    onMenu: ((Tab, IntOffset) -> Unit)? = null,
    iconOf: @Composable (Tab) -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val scroll = rememberScrollState()
    Row(
        modifier.fillMaxWidth().background(Kit.colors.tabInactive)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(scroll)) {
            group.tabs.forEach { tab ->
                key(tab.key) { StripTab(tab, tab.key == group.active, titleOf(tab), isDirty(tab), scroll.maxValue, onActivate, onKeep, onClose, onMenu, iconOf) }
            }
        }
        if (scroll.maxValue > 0) OpenDocuments(group, titleOf, isDirty, onActivate)
        trailing()
    }
}

/** Every open document of the group, most recent first: where a tab scrolled out of sight is one tap away. */
@Composable
private fun OpenDocuments(group: EditorGroup, titleOf: @Composable (Tab) -> String, isDirty: (Tab) -> Boolean, onActivate: (Tab) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val tabs = group.mru.mapNotNull { uri -> group.tabs.find { it.uri == uri } }
    val items = tabs.map { tab ->
        val title = titleOf(tab)
        KitMenuItem.Action(if (isDirty(tab)) stringResource(R.string.wshell_switcher_dirty, title) else title, { onActivate(tab) }, checked = tab.key == group.active)
    }
    Box {
        KitIconButton(iconFor("chevron_down"), stringResource(R.string.wshell_open_documents), { open = true })
        KitMenu(open, { open = false }, items)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StripTab(
    tab: Tab,
    selected: Boolean,
    title: String,
    dirty: Boolean,
    /** The strip's scroll extent: when the window or the font changes it, the active tab is brought back into view. */
    extent: Int,
    onActivate: (Tab) -> Unit,
    onKeep: (Tab) -> Unit,
    onClose: (Tab) -> Unit,
    onMenu: ((Tab, IntOffset) -> Unit)?,
    iconOf: @Composable (Tab) -> Unit,
) {
    val colors = Kit.colors
    val source = remember { MutableInteractionSource() }
    val flags = source.collectFlags()
    val press = rememberPressPoint()
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(selected, extent) { if (selected) bring.bringIntoView() }
    val textColor = if (selected) colors.tabActiveText else colors.tabInactiveText
    Row(
        Modifier.height(Kit.control.tabHeight).kitTag("doc-tab").bringIntoViewRequester(bring)
            .background(if (selected) colors.tabActive else colors.tabInactive)
            .tabLines(selected)
            .kitPressPoint(press, onSecondary = onMenu?.let { menu -> { at -> menu(tab, at) } })
            .combinedClickable(
                source, null, role = Role.Tab,
                onClick = { onActivate(tab) },
                onDoubleClick = { onKeep(tab) },
                onLongClick = onMenu?.let { menu -> { menu(tab, press.at) } },
            )
            .kitFocusRing(flags.focused, RectangleShape)
            .semantics { this.selected = selected }
            .padding(start = Kit.control.hPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        iconOf(tab)
        BasicText(
            title,
            Modifier.padding(start = Kit.space.s).widthIn(max = ShellTokens.tabMaxWidth),
            style = Kit.text.body.copy(color = textColor, fontStyle = if (tab.state == TabState.PREVIEW) FontStyle.Italic else FontStyle.Normal),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CloseButton(title, dirty, selected, flags.hovered) { onClose(tab) }
    }
}

/**
 * The cross of the active tab, or of any tab under the pointer; a dot while the document has unsaved
 * changes (VS Code's rule). Its box is kept when nothing shows, so a tab does not change width as the
 * pointer moves over it.
 */
@Composable
private fun CloseButton(title: String, dirty: Boolean, active: Boolean, hovered: Boolean, onClose: () -> Unit) {
    val colors = Kit.colors
    val label = stringResource(if (dirty) R.string.wshell_tab_dirty_close else R.string.shell_document_close, title)
    val dot = Kit.space.s
    Box(Modifier.size(Kit.control.hitBox).kitPressable(onClose).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        when {
            dirty && !hovered -> Box(Modifier.size(dot).drawBehind { drawCircle(colors.plainText) })
            active || hovered -> Image(iconFor("close"), null, Modifier.size(Kit.control.rowIcon), colorFilter = ColorFilter.tint(colors.textMuted))
        }
    }
}

/** The accent line on top of the active tab, a hairline on the right of every tab, and one under the others (the active one is open to the editor). */
@Composable
private fun Modifier.tabLines(selected: Boolean): Modifier {
    val colors = Kit.colors
    val line = Kit.hairline
    return drawBehind {
        val w = line.toPx()
        drawRect(colors.panelBorder, Offset(size.width - w, 0f), Size(w, size.height))
        if (selected) drawRect(colors.tabActiveBorder, Offset.Zero, Size(size.width, w))
        else drawRect(colors.panelBorder, Offset(0f, size.height - w), Size(size.width, w))
    }
}
