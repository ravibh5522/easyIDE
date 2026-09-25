package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.IntOffset
import dev.easyide.app.R
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.ui.foundation.LocalSettings
import dev.easyide.app.ui.foundation.LocalSettingsEditor
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.app.ui.screens.workspace.files.LocalIgnoreIndex
import dev.easyide.app.ui.screens.workspace.files.TreeFilter
import dev.easyide.sandbox.files.FileNode
import dev.easyide.sandbox.git.GitChangeType

/**
 * The explorer: a header with new-file / new-folder / refresh / filter, then the tree.
 *
 * Rows are the kit's list row (the row token tall, indent per level) with a hairline guide
 * under each ancestor, so at depth 3+ it stays obvious which parent a file belongs to. A file with a
 * git change wears its status letter at the end of the row ([changes], by project path).
 */
@Composable
fun FileTreePane(
    state: WorkspaceUiState,
    onFileOpened: (FileNode) -> Unit,
    onDirectoryToggled: (FileNode) -> Unit,
    onNodeMenu: (FileNode, IntOffset) -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRefresh: () -> Unit,
    inline: InlineEditSpec,
    modifier: Modifier = Modifier,
    changes: Map<String, GitChangeType> = emptyMap(),
    onNodeAction: (FileAction, FileNode) -> Unit = { _, _ -> },
) {
    val colors = Kit.colors
    val settings = LocalSettings.current
    val settingsEditor = LocalSettingsEditor.current
    val hideHidden = settings[SettingsSchema.explorerHideHidden]
    val hideIgnored = settings[SettingsSchema.explorerHideIgnored]
    val ignore = LocalIgnoreIndex.current
    val filter = remember(hideHidden, hideIgnored, ignore) { TreeFilter(hideHidden, hideIgnored, ignore) }
    val marks = remember(state.openTabs, changes) { TreeMarks(state.openTabs.filter { it.isDirty }.map { it.relativePath }.toSet(), changes) }
    var focused by remember { mutableStateOf<FileNode?>(null) }
    val focus = remember { FocusRequester() }
    val collapseAll = { state.expandedFolders().forEach(onDirectoryToggled) }

    Column(modifier = modifier.fillMaxSize().background(colors.panel)) {
        ExplorerHeader(
            projectName = state.projectName,
            onNewFile = onNewFile,
            onNewFolder = onNewFolder,
            onRefresh = onRefresh,
            onCollapseAll = collapseAll,
            hideHidden = hideHidden,
            hideIgnored = hideIgnored,
            onHideHiddenChange = { settingsEditor?.set(SettingsSchema.explorerHideHidden, it) },
            onHideIgnoredChange = { settingsEditor?.set(SettingsSchema.explorerHideIgnored, it) },
        )

        LazyColumn(modifier = Modifier.fillMaxSize().focusRequester(focus).focusable().onKeyEvent { treeKey(it, focused, state.clipboard != null, onNodeAction) }) {
            renderNodes(
                nodes = state.tree,
                parent = "",
                depth = 0,
                filter = filter,
                state = state,
                onFileOpened = onFileOpened,
                onDirectoryToggled = onDirectoryToggled,
                onNodeMenu = onNodeMenu,
                inline = inline,
                marks = marks,
                focused = focused,
                onFocus = { focused = it; focus.requestFocus() },
            )
        }
    }
}

@Composable
private fun ExplorerHeader(
    projectName: String,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRefresh: () -> Unit,
    onCollapseAll: () -> Unit,
    hideHidden: Boolean,
    hideIgnored: Boolean,
    onHideHiddenChange: (Boolean) -> Unit,
    onHideIgnoredChange: (Boolean) -> Unit,
) {
    PanelTitleRow(projectName.ifEmpty { stringResource(R.string.wp_files_title) }) {
        KitIconButton(Icons.Filled.NoteAdd, stringResource(R.string.wp_new_file), onNewFile)
        KitIconButton(Icons.Filled.CreateNewFolder, stringResource(R.string.wp_new_folder), onNewFolder)
        KitIconButton(Icons.Filled.Refresh, stringResource(R.string.wp_refresh), onRefresh)
        KitIconButton(Icons.Filled.UnfoldLess, stringResource(R.string.gitui_collapse_all), onCollapseAll)
        FilterMenu(hideHidden, hideIgnored, onHideHiddenChange, onHideIgnoredChange)
    }
}

/** The explorer's two persisted filters, as checkable items behind one header button. */
@Composable
private fun FilterMenu(
    hideHidden: Boolean,
    hideIgnored: Boolean,
    onHideHiddenChange: (Boolean) -> Unit,
    onHideIgnoredChange: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        KitIconButton(Icons.Filled.FilterList, stringResource(R.string.explorer_filter), { open = true })
        KitMenu(
            expanded = open,
            onDismiss = { open = false },
            items = listOf(
                KitMenuItem.Action(stringResource(R.string.explorer_hide_hidden), { onHideHiddenChange(!hideHidden) }, checked = hideHidden),
                KitMenuItem.Action(stringResource(R.string.explorer_hide_ignored), { onHideIgnoredChange(!hideIgnored) }, checked = hideIgnored),
            ),
        )
    }
}

/**
 * Flattens the visible tree into list items. Recursion happens here because
 * nested LazyColumns cannot measure inside one another. An inline edit shows as
 * a name field first among the children of the directory it creates in, or in
 * place of the row it renames.
 */
private fun LazyListScope.renderNodes(
    nodes: List<FileNode>,
    parent: String,
    depth: Int,
    filter: TreeFilter,
    state: WorkspaceUiState,
    onFileOpened: (FileNode) -> Unit,
    onDirectoryToggled: (FileNode) -> Unit,
    onNodeMenu: (FileNode, IntOffset) -> Unit,
    inline: InlineEditSpec,
    marks: TreeMarks,
    focused: FileNode?,
    onFocus: (FileNode) -> Unit,
) {
    val edit = inline.edit
    if (edit != null && edit.parent == parent) {
        item(key = "inline:$parent") { InlineNameRow(edit, depth, { inline.onCommit(edit, it) }, inline.onCancel) }
    }
    nodes.filter(filter::shows).forEach { node ->
        if (edit is InlineEdit.Rename && edit.node.relativePath == node.relativePath) {
            item(key = node.relativePath) { InlineNameRow(edit, depth, { inline.onCommit(edit, it) }, inline.onCancel) }
        } else {
            item(key = node.relativePath) {
                FileTreeRow(
                    node = node,
                    depth = depth,
                    expanded = node.relativePath in state.expandedDirs,
                    selected = state.activeTabPath == node.relativePath || focused?.relativePath == node.relativePath,
                    change = marks.changes[node.relativePath],
                    dirty = node.relativePath in marks.dirty,
                    holdsChanges = node.isDirectory && node.relativePath in marks.changedDirs,
                    onClick = { onFocus(node); if (node.isDirectory) onDirectoryToggled(node) else onFileOpened(node) },
                    onMenu = { at -> onFocus(node); onNodeMenu(node, at) },
                )
            }
        }
        if (node.isDirectory && node.relativePath in state.expandedDirs) {
            renderNodes(
                nodes = state.childrenByDir[node.relativePath].orEmpty(),
                parent = node.relativePath,
                depth = depth + 1,
                filter = filter,
                state = state,
                onFileOpened = onFileOpened,
                onDirectoryToggled = onDirectoryToggled,
                onNodeMenu = onNodeMenu,
                inline = inline,
                marks = marks,
                focused = focused,
                onFocus = onFocus,
            )
        }
    }
}
