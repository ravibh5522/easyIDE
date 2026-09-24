package dev.easyide.app.ui.screens.workspace.ext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.easyide.app.R
import dev.easyide.app.extensions.ExtensionUiPolicy
import dev.easyide.app.extensions.adapters.MenuEntry
import dev.easyide.app.extensions.adapters.StatusItem
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.extensions.contrib.CommandIcon
import dev.easyide.extensions.contrib.StatusBarAlignment

/**
 * easyIDE icon tokens (sdk-reference `commands[].icon`) -> Material icons: the one table.
 * A token not listed here, or a package SVG, falls back to the command's short title.
 */
private val ICON_TOKENS: Map<String, ImageVector> = mapOf(
    "play_arrow" to Icons.Filled.PlayArrow, "stop" to Icons.Filled.Stop, "refresh" to Icons.Filled.Refresh,
    "sync" to Icons.Filled.Sync, "add" to Icons.Filled.Add, "check" to Icons.Filled.Check, "close" to Icons.Filled.Close,
    "save" to Icons.Filled.Save, "edit" to Icons.Filled.Edit, "delete" to Icons.Filled.Delete,
    "content_copy" to Icons.Filled.ContentCopy, "search" to Icons.Filled.Search, "settings" to Icons.Filled.Settings,
    "terminal" to Icons.Filled.Terminal, "code" to Icons.Filled.Code, "build" to Icons.Filled.Build,
    "bug_report" to Icons.Filled.BugReport, "history" to Icons.Filled.History, "upload" to Icons.Filled.Upload,
    "download" to Icons.Filled.Download, "arrow_upward" to Icons.Filled.ArrowUpward, "arrow_downward" to Icons.Filled.ArrowDownward,
)

private fun MenuEntry.icon(): ImageVector? = (command.icon as? CommandIcon.Token)?.let { ICON_TOKENS[it.name] }

private fun MenuEntry.shortLabel(): String = command.shortTitle ?: command.title

/**
 * `editor/title` actions at the end of the editor tab bar: the first
 * [ExtensionUiPolicy.EDITOR_TITLE_MAX_INLINE] `navigation` entries inline, the rest in an
 * overflow menu (VS Code's split), which also holds the `editor/context` entries so they
 * are reachable by touch.
 */
@Composable
fun EditorTitleActions(title: List<MenuEntry>, context: List<MenuEntry>, onRun: (MenuEntry) -> Unit) {
    val inline = title.filter { it.group == dev.easyide.extensions.contrib.MenuIds.NAVIGATION_GROUP }.take(ExtensionUiPolicy.EDITOR_TITLE_MAX_INLINE)
    val overflow = title - inline.toSet()
    Row(verticalAlignment = Alignment.CenterVertically) {
        inline.forEach { ActionButton(it, onRun) }
        if (overflow.isNotEmpty() || context.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            Box {
                KitIconButton(Icons.Filled.MoreVert, stringResource(R.string.ext_editor_more_actions), { open = true })
                ContributedMenu(open, listOf(overflow, context).filter { it.isNotEmpty() }, onRun) { open = false }
            }
        }
    }
}

/** An icon action when the command's token is known, otherwise its short title as a text action. */
@Composable
private fun ActionButton(entry: MenuEntry, onRun: (MenuEntry) -> Unit) {
    val icon = entry.icon()
    if (icon != null) {
        KitIconButton(icon, entry.command.title, { onRun(entry) }, enabled = entry.enabled)
    } else {
        KitButton(entry.shortLabel(), { onRun(entry) }, style = KitButtonStyle.Ghost, enabled = entry.enabled)
    }
}

/** A menu of contributed entries in sections (a divider between groups of [sections]), under [at] when it answers a press. */
@Composable
fun ContributedMenu(expanded: Boolean, sections: List<List<MenuEntry>>, onRun: (MenuEntry) -> Unit, at: IntOffset? = null, onDismiss: () -> Unit) {
    KitMenu(expanded, onDismiss, contributedItems(sections, onRun), at = at)
}

/** One action per entry, a divider between sections; a disabled entry stays visible but inert. */
internal fun contributedItems(sections: List<List<MenuEntry>>, onRun: (MenuEntry) -> Unit): List<KitMenuItem> =
    sections.flatMapIndexed { i, section ->
        val actions = section.map { entry -> KitMenuItem.Action(entry.command.title, { onRun(entry) }, enabled = entry.enabled) }
        if (i > 0) listOf(KitMenuItem.Divider) + actions else actions
    }

/** `editor/touchToolbar`: a slim bar above the key row, shown for touch input only (EXT-33). */
@Composable
fun TouchToolbar(entries: List<MenuEntry>, onRun: (MenuEntry) -> Unit) {
    if (entries.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().background(Kit.colors.panel).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { ActionButton(it, onRun) }
    }
}

/** Contributed status bar items of one side; a tap runs the item's command. */
@Composable
fun StatusItemsRow(items: List<StatusItem>, alignment: StatusBarAlignment, onRun: (String) -> Unit) {
    val colors = Kit.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Kit.space.m), verticalAlignment = Alignment.CenterVertically) {
        items.filter { it.alignment == alignment }.forEach { item ->
            val command = item.command
            BasicText(
                text = item.text,
                style = Kit.text.label.copy(color = colors.statusBarText),
                maxLines = 1,
                modifier = if (command != null) Modifier.clickable(role = Role.Button) { onRun(command) } else Modifier,
            )
        }
    }
}

/** Groups menu entries into sections by `group`, keeping MenuModel's order. */
fun List<MenuEntry>.sections(): List<List<MenuEntry>> =
    fold(ArrayList<MutableList<MenuEntry>>()) { acc, e ->
        if (acc.isEmpty() || acc.last().first().group != e.group) acc.add(mutableListOf(e)) else acc.last().add(e)
        acc
    }

