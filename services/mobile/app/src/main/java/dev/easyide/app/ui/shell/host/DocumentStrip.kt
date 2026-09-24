package dev.easyide.app.ui.shell.host

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.collectFlags
import dev.easyide.app.ui.kit.kitFocusRing
import dev.easyide.app.ui.kit.kitPressable
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.shell.EditorGroup
import dev.easyide.app.ui.shell.Tab
import dev.easyide.app.ui.shell.TabState
import dev.easyide.app.ui.theme.IconSize

/**
 * The tab strip of the one editor group on a wide window: a scrolling row of document tabs. The
 * preview tab is italic (the next preview replaces it); a tap activates a tab, a double tap keeps a
 * preview, the cross closes. The strip is at least the touch floor tall so the cross is a real
 * target on a tablet.
 */
@Composable
fun DocumentStrip(
    group: EditorGroup,
    titleOf: (Tab) -> String,
    onActivate: (Tab) -> Unit,
    onKeep: (Tab) -> Unit,
    onClose: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().background(Kit.colors.tabInactive)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
            .horizontalScroll(rememberScrollState()),
    ) {
        group.tabs.forEach { tab ->
            key(tab.key) { StripTab(tab, tab.key == group.active, titleOf(tab), onActivate, onKeep, onClose) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StripTab(
    tab: Tab,
    selected: Boolean,
    title: String,
    onActivate: (Tab) -> Unit,
    onKeep: (Tab) -> Unit,
    onClose: (Tab) -> Unit,
) {
    val colors = Kit.colors
    val source = remember { MutableInteractionSource() }
    val flags = source.collectFlags()
    Row(
        Modifier.height(maxOf(Kit.control.tab, Kit.metrics.touchFloor)).kitTag("doc-tab")
            .background(if (selected) colors.tabActive else colors.tabInactive)
            .underline(selected)
            .combinedClickable(source, null, role = Role.Tab, onClick = { onActivate(tab) }, onDoubleClick = { onKeep(tab) })
            .kitFocusRing(flags.focused, RectangleShape)
            .semantics { this.selected = selected }
            .padding(start = Kit.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            title,
            style = Kit.type.labelLarge.copy(
                color = if (selected) colors.tabActiveText else colors.tabInactiveText,
                fontStyle = if (tab.state == TabState.PREVIEW) FontStyle.Italic else FontStyle.Normal,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.size(Kit.metrics.touchFloor).kitPressable({ onClose(tab) }), contentAlignment = Alignment.Center) {
            Image(
                Icons.Filled.Close, stringResource(R.string.shell_document_close, title), Modifier.size(IconSize.s),
                colorFilter = ColorFilter.tint(colors.textMuted),
            )
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
