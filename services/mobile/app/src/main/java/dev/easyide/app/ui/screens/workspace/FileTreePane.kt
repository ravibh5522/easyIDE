package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    onNewFile: (FileNode?) -> Unit,
    onNewFolder: (FileNode?) -> Unit,
    onRefresh: () -> Unit,
    inline: InlineEditSpec,
    modifier: Modifier = Modifier,
    changes: Map<String, GitChangeType> = emptyMap(),
) {
    val colors = Kit.colors
    val settings = LocalSettings.current
    val settingsEditor = LocalSettingsEditor.current
    val hideHidden = settings[SettingsSchema.explorerHideHidden]
    val hideIgnored = settings[SettingsSchema.explorerHideIgnored]
    val ignore = LocalIgnoreIndex.current
    val filter = remember(hideHidden, hideIgnored, ignore) { TreeFilter(hideHidden, hideIgnored, ignore) }
    // Like VS Code: a new entry goes where the explorer is pointing, the last tapped folder or file's
    // folder, and once the editor moves to another file the explorer follows it.
    var focus by remember { mutableStateOf<FileNode?>(null) }
    LaunchedEffect(state.activeTabPath) { focus = null }
    val target = explorerTarget(state, focus)

    Column(modifier = modifier.fillMaxSize().background(colors.panel)) {
        ExplorerHeader(
            projectName = state.projectName,
            onNewFile = { onNewFile(target) },
            onNewFolder = { onNewFolder(target) },
            onRefresh = onRefresh,
            hideHidden = hideHidden,
            hideIgnored = hideIgnored,
            onHideHiddenChange = { settingsEditor?.set(SettingsSchema.explorerHideHidden, it) },
            onHideIgnoredChange = { settingsEditor?.set(SettingsSchema.explorerHideIgnored, it) },
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            renderNodes(
                nodes = state.tree,
                parent = "",
                depth = 0,
                filter = filter,
                state = state,
                onFileOpened = { focus = it; onFileOpened(it) },
                onDirectoryToggled = { focus = it; onDirectoryToggled(it) },
                onNodeMenu = onNodeMenu,
                inline = inline,
                changes = changes,
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
    hideHidden: Boolean,
    hideIgnored: Boolean,
    onHideHiddenChange: (Boolean) -> Unit,
    onHideIgnoredChange: (Boolean) -> Unit,
) {
    PanelTitleRow(projectName.ifEmpty { stringResource(R.string.wp_files_title) }) {
        KitIconButton(Icons.Filled.NoteAdd, stringResource(R.string.wp_new_file), onNewFile)
        KitIconButton(Icons.Filled.CreateNewFolder, stringResource(R.string.wp_new_folder), onNewFolder)
        KitIconButton(Icons.Filled.Refresh, stringResource(R.string.wp_refresh), onRefresh)
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
    changes: Map<String, GitChangeType>,
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
                    selected = state.activeTabPath == node.relativePath,
                    change = changes[node.relativePath],
                    onClick = { if (node.isDirectory) onDirectoryToggled(node) else onFileOpened(node) },
                    onMenu = { at -> onNodeMenu(node, at) },
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
                changes = changes,
            )
        }
    }
}
