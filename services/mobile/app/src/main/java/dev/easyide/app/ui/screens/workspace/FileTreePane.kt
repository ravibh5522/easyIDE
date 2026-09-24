package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.ui.foundation.LocalSettings
import dev.easyide.app.ui.foundation.LocalSettingsEditor
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.app.ui.kit.collectFlags
import dev.easyide.app.ui.kit.kitFocusRing
import dev.easyide.app.ui.kit.kitMarker
import dev.easyide.app.ui.kit.kitStateLayer
import dev.easyide.app.ui.screens.workspace.files.FileIcon
import dev.easyide.app.ui.screens.workspace.files.LocalIgnoreIndex
import dev.easyide.app.ui.screens.workspace.files.TreeFilter
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.rememberThemedFileIcon
import dev.easyide.sandbox.files.FileNode

/**
 * The explorer: a header with new-file / new-folder / refresh / filter, then the tree.
 *
 * Nesting is drawn with per-level guide lines rather than plain indentation, so
 * at depth 3+ it stays obvious which parent a file belongs to. Every row is at
 * least the touch floor tall, so the tree is usable in a phone's modal sheet.
 */
@Composable
fun FileTreePane(
    state: WorkspaceUiState,
    onFileOpened: (FileNode) -> Unit,
    onDirectoryToggled: (FileNode) -> Unit,
    onNodeMenu: (FileNode) -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Kit.colors
    val settings = LocalSettings.current
    val settingsEditor = LocalSettingsEditor.current
    val hideHidden = settings[SettingsSchema.explorerHideHidden]
    val hideIgnored = settings[SettingsSchema.explorerHideIgnored]
    val ignore = LocalIgnoreIndex.current
    val filter = remember(hideHidden, hideIgnored, ignore) { TreeFilter(hideHidden, hideIgnored, ignore) }

    Column(modifier = modifier.fillMaxSize().background(colors.panel)) {
        ExplorerHeader(
            projectName = state.projectName,
            onNewFile = onNewFile,
            onNewFolder = onNewFolder,
            onRefresh = onRefresh,
            hideHidden = hideHidden,
            hideIgnored = hideIgnored,
            onHideHiddenChange = { settingsEditor?.set(SettingsSchema.explorerHideHidden, it) },
            onHideIgnoredChange = { settingsEditor?.set(SettingsSchema.explorerHideIgnored, it) },
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            renderNodes(
                nodes = state.tree,
                depth = 0,
                filter = filter,
                state = state,
                onFileOpened = onFileOpened,
                onDirectoryToggled = onDirectoryToggled,
                onNodeMenu = onNodeMenu,
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
 * nested LazyColumns cannot measure inside one another.
 */
private fun LazyListScope.renderNodes(
    nodes: List<FileNode>,
    depth: Int,
    filter: TreeFilter,
    state: WorkspaceUiState,
    onFileOpened: (FileNode) -> Unit,
    onDirectoryToggled: (FileNode) -> Unit,
    onNodeMenu: (FileNode) -> Unit,
) {
    nodes.filter(filter::shows).forEach { node ->
        item(key = node.relativePath) {
            FileTreeRow(
                node = node,
                depth = depth,
                expanded = node.relativePath in state.expandedDirs,
                selected = state.activeTabPath == node.relativePath,
                onClick = { if (node.isDirectory) onDirectoryToggled(node) else onFileOpened(node) },
                onLongClick = { onNodeMenu(node) },
            )
        }
        if (node.isDirectory && node.relativePath in state.expandedDirs) {
            renderNodes(
                nodes = state.childrenByDir[node.relativePath].orEmpty(),
                depth = depth + 1,
                filter = filter,
                state = state,
                onFileOpened = onFileOpened,
                onDirectoryToggled = onDirectoryToggled,
                onNodeMenu = onNodeMenu,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeRow(
    node: FileNode,
    depth: Int,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = Kit.colors
    val space = Kit.space
    val muted = ColorFilter.tint(colors.textMuted)
    val interaction = remember { MutableInteractionSource() }
    val flags = interaction.collectFlags()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Intrinsic height so the indent guides fill exactly the row, however tall the text scale makes it.
            .height(IntrinsicSize.Min)
            .defaultMinSize(minHeight = panelRowHeight())
            .then(if (selected) Modifier.background(colors.listSelection) else Modifier)
            .kitMarker(selected)
            .kitStateLayer(flags, true, colors.plainText)
            .kitFocusRing(flags.focused, RectangleShape)
            .semantics { this.selected = selected }
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(end = space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IndentGuides(depth)

        // `workbench.iconTheme`: the theme's image replaces the built-in glyph when it has one.
        val themed = rememberThemedFileIcon(node.name, node.isDirectory, expanded)
        if (node.isDirectory) {
            Image(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                null,
                Modifier.size(IconSize.xs),
                colorFilter = muted,
            )
            if (themed != null) {
                Image(themed, null, Modifier.padding(start = space.xxs).size(IconSize.xs))
            } else Image(
                if (expanded) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                null,
                Modifier.padding(start = space.xxs).size(IconSize.xs),
                // An open folder takes the accent, so the path to the open file is traceable.
                colorFilter = if (expanded) ColorFilter.tint(colors.accent) else muted,
            )
        } else if (themed != null) {
            Image(themed, null, Modifier.size(IconSize.xs))
        } else {
            FileIcon(node.name, size = IconSize.xs)
        }

        BasicText(
            text = node.name,
            style = Kit.type.bodySmall.copy(color = if (selected) colors.listSelectionText else colors.plainText),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = space.s),
        )
    }
}

/** One thin vertical rule per ancestor level, as VS Code draws nesting. */
@Composable
private fun IndentGuides(depth: Int) {
    val colors = Kit.colors
    val space = Kit.space
    // Half the chevron glyph past the base inset, so each guide sits under its parent's chevron rather than beside it.
    val inset = space.xs + IconSize.xs / 2
    Row {
        repeat(depth) {
            Box(Modifier.width(space.m).fillMaxHeight().padding(start = inset)) {
                Box(Modifier.width(Kit.hairline).fillMaxHeight().background(colors.indentGuide))
            }
        }
        Box(Modifier.width(space.xs))
    }
}
