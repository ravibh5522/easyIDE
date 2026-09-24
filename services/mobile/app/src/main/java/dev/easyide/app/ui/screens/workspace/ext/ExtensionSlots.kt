package dev.easyide.app.ui.screens.workspace.ext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.extensions.ExtensionUiPolicy
import dev.easyide.app.extensions.adapters.MenuEntry
import dev.easyide.app.extensions.adapters.StatusItem
import dev.easyide.app.ui.theme.editorColors
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
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.ext_editor_more_actions),
                    tint = editorColors.gutterText,
                    modifier = Modifier.clickable { open = true }.minimumInteractiveComponentSize().size(IconSize.m),
                )
                ContributedMenu(open, listOf(overflow, context).filter { it.isNotEmpty() }, onRun) { open = false }
            }
        }
    }
}

@Composable
private fun ActionButton(entry: MenuEntry, onRun: (MenuEntry) -> Unit) {
    val colors = editorColors
    val icon = entry.icon()
    val modifier = Modifier.clickable(enabled = entry.enabled) { onRun(entry) }.minimumInteractiveComponentSize()
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (icon != null) {
            Icon(icon, contentDescription = entry.command.title, tint = if (entry.enabled) colors.plainText else colors.gutterText, modifier = Modifier.size(IconSize.m))
        } else {
            Text(entry.shortLabel(), style = MaterialTheme.typography.labelSmall, color = if (entry.enabled) colors.plainText else colors.gutterText,
                modifier = Modifier.padding(horizontal = Spacing.xs))
        }
    }
}

/** A dropdown of contributed entries in sections (dividers between groups of [sections]). */
@Composable
fun ContributedMenu(expanded: Boolean, sections: List<List<MenuEntry>>, onRun: (MenuEntry) -> Unit, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        sections.forEachIndexed { i, section ->
            if (i > 0) HorizontalDivider()
            section.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.command.title) },
                    enabled = entry.enabled,
                    onClick = { onDismiss(); onRun(entry) },
                )
            }
        }
    }
}

/** `editor/touchToolbar`: a slim bar above the key row, shown for touch input only (EXT-33). */
@Composable
fun TouchToolbar(entries: List<MenuEntry>, onRun: (MenuEntry) -> Unit) {
    if (entries.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().background(editorColors.panel).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { ActionButton(it, onRun) }
    }
}

/** Contributed status bar items of one side; a tap runs the item's command. */
@Composable
fun StatusItemsRow(items: List<StatusItem>, alignment: StatusBarAlignment, onRun: (String) -> Unit) {
    val colors = editorColors
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        items.filter { it.alignment == alignment }.forEach { item ->
            val command = item.command
            Text(
                text = item.text,
                style = MaterialTheme.typography.labelSmall,
                color = colors.statusBarText,
                maxLines = 1,
                modifier = if (command != null) Modifier.clickable { onRun(command) } else Modifier,
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

