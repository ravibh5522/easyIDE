package dev.easyide.app.ui.shell.host

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.app.ui.kit.kitPressable
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.EditorGroup
import dev.easyide.app.ui.theme.IconSize

/**
 * A phone's replacement for the tab strip (layout-spec.md section 4.1): the active document's title
 * with the count of open ones, which opens the list of them, most recently used first; the active
 * group's actions sit at the end. The list ends with closing the current document. A long press on the
 * title opens the document's own menu (keep, pin, close others, close all), the phone's counterpart of
 * a tab's menu.
 */
@Composable
internal fun DocumentSwitcher(
    group: EditorGroup,
    title: String,
    titleOf: @Composable (DocumentUri) -> String,
    callbacks: GroupCallbacks,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = Kit.colors
    var open by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    val label = stringResource(R.string.wshell_switcher_label, title, group.tabs.size)
    val close = stringResource(R.string.shell_document_close, title)
    val entries = group.mru.map { it to titleOf(it) }
    Row(
        Modifier.background(colors.panel)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .defaultMinSize(minHeight = Kit.metrics.touchFloor),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.defaultMinSize(minHeight = Kit.metrics.touchFloor).kitTag("doc-switcher")
                    .kitPressable(
                        { open = group.tabs.isNotEmpty() },
                        role = Role.Button,
                        onLongClick = if (callbacks.hasTabMenu) ({ actionsOpen = group.tabs.isNotEmpty() }) else null,
                    )
                    .semantics(mergeDescendants = true) { contentDescription = label }
                    .padding(horizontal = Kit.space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(title, Modifier.weight(1f, fill = false), style = Kit.type.titleSmall.copy(color = colors.plainText), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (group.tabs.size > 1) BasicText(" ${group.tabs.size}", style = Kit.type.labelMedium.copy(color = colors.textMuted))
                if (group.tabs.isNotEmpty()) Image(Icons.Filled.ExpandMore, null, Modifier.size(IconSize.m), colorFilter = ColorFilter.tint(colors.textMuted))
            }
            KitMenu(
                open, { open = false },
                entries.map { (uri, name) ->
                    KitMenuItem.Action(if (callbacks.isDirty(uri)) stringResource(R.string.wshell_switcher_dirty, name) else name, { callbacks.activate(uri) }, checked = uri == group.active)
                } + KitMenuItem.Divider + KitMenuItem.Action(close, { group.active?.let(callbacks::close) }, icon = Icons.Filled.Close),
            )
            group.active?.let { TabMenu(actionsOpen, { actionsOpen = false }, group, it, callbacks, TabMenuContext(canBeside = false, canMoveNext = false)) }
        }
        actions()
    }
}
