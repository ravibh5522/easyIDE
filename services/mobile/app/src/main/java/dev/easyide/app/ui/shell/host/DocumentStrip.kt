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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
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
import dev.easyide.app.ui.kit.collectFlags
import dev.easyide.app.ui.kit.kitFocusRing
import dev.easyide.app.ui.kit.kitPressPoint
import dev.easyide.app.ui.kit.kitPressable
import dev.easyide.app.ui.kit.rememberPressPoint
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.shell.EditorGroup
import dev.easyide.app.ui.shell.Tab
import dev.easyide.app.ui.shell.TabState

/**
 * The tab strip of one editor group on a wide window: a scrolling row of document tabs, then the group's
 * [trailing] actions, which stay put while the tabs scroll. The preview tab is italic (the next preview
 * replaces it); a tap activates a tab, a double tap keeps a preview, a long press or right click opens
 * the tab's menu, the cross closes, and a document with unsaved changes says so in the close button's
 * name. A tab is `tabHeight` tall and its cross a `hitBox` square; the touch region beyond that is the
 * theme's touch floor.
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
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().background(Kit.colors.tabInactive)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            group.tabs.forEach { tab ->
                key(tab.key) { StripTab(tab, tab.key == group.active, titleOf(tab), isDirty(tab), onActivate, onKeep, onClose, onMenu) }
            }
        }
        trailing()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StripTab(
    tab: Tab,
    selected: Boolean,
    title: String,
    dirty: Boolean,
    onActivate: (Tab) -> Unit,
    onKeep: (Tab) -> Unit,
    onClose: (Tab) -> Unit,
    onMenu: ((Tab, IntOffset) -> Unit)?,
) {
    val colors = Kit.colors
    val source = remember { MutableInteractionSource() }
    val flags = source.collectFlags()
    val press = rememberPressPoint()
    Row(
        Modifier.height(Kit.control.tabHeight).kitTag("doc-tab")
            .background(if (selected) colors.tabActive else colors.tabInactive)
            .underline(selected)
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
        BasicText(
            title,
            Modifier.widthIn(max = ShellTokens.tabMaxWidth),
            style = Kit.text.title.copy(
                color = if (selected) colors.tabActiveText else colors.tabInactiveText,
                fontStyle = if (tab.state == TabState.PREVIEW) FontStyle.Italic else FontStyle.Normal,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.size(Kit.control.hitBox).kitPressable({ onClose(tab) }), contentAlignment = Alignment.Center) {
            val closeLabel = stringResource(if (dirty) R.string.wshell_tab_dirty_close else R.string.shell_document_close, title)
            if (dirty) {
                Box(Modifier.size(Kit.space.s).background(colors.plainText, CircleShape).semantics { contentDescription = closeLabel })
            } else {
                Image(iconFor("close"), closeLabel, Modifier.size(Kit.control.rowIcon), colorFilter = ColorFilter.tint(colors.textMuted))
            }
        }
    }
}

/** The accent bar under the active tab, matching the kit's underline tabs. */
@Composable
private fun Modifier.underline(selected: Boolean): Modifier {
    if (!selected) return this
    val color = Kit.colors.tabActiveBorder
    val thickness = Kit.marker
    return drawBehind { drawRect(color, Offset(0f, size.height - thickness.toPx()), Size(size.width, thickness.toPx())) }
}
