package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.ui.theme.ControlSize
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.rememberThemedFileIcon
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.Stroke
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.app.ui.theme.sectionHeader
import dev.easyide.sandbox.files.FileNode

/**
 * The explorer: a header with new-file / new-folder / refresh, then the tree.
 *
 * Nesting is drawn with per-level guide lines rather than plain indentation, so
 * at depth 3+ it stays obvious which parent a file belongs to.
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
    val colors = editorColors

    Column(modifier = modifier.fillMaxSize().background(colors.panel)) {
        ExplorerHeader(
            projectName = state.projectName,
            onNewFile = onNewFile,
            onNewFolder = onNewFolder,
            onRefresh = onRefresh,
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            renderNodes(
                nodes = state.tree,
                depth = 0,
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
) {
    val colors = editorColors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.m, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = projectName.uppercase().ifEmpty { "EXPLORER" },
            style = MaterialTheme.typography.sectionHeader,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        HeaderAction(Icons.Filled.NoteAdd, "New file", onNewFile)
        HeaderAction(Icons.Filled.CreateNewFolder, "New folder", onNewFolder)
        HeaderAction(Icons.Filled.Refresh, "Refresh", onRefresh)
    }
}

/** Icon-button sized (not glyph sized) so the header actions are real touch targets. */
@Composable
private fun HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val colors = editorColors
    IconButton(onClick = onClick, modifier = Modifier.size(ControlSize.headerAction)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = colors.textMuted,
            modifier = Modifier.size(IconSize.s),
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
    state: WorkspaceUiState,
    onFileOpened: (FileNode) -> Unit,
    onDirectoryToggled: (FileNode) -> Unit,
    onNodeMenu: (FileNode) -> Unit,
) {
    nodes.forEach { node ->
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
    val colors = editorColors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Fixed height so the indent guides have a bounded height to fill.
            .height(ControlSize.row)
            // The selection pill: inset from the pane edges and rounded, so the
            // open file reads as a marked item rather than a recoloured stripe.
            .padding(horizontal = Spacing.xs)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (selected) colors.listSelection else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(end = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IndentGuides(depth)

        // `workbench.iconTheme`: the theme's image replaces the file or folder glyph when it has one.
        val themed = rememberThemedFileIcon(node.name, node.isDirectory, expanded)
        if (themed != null && !node.isDirectory) {
            Image(themed, contentDescription = null, modifier = Modifier.size(IconSize.xs))
        } else Icon(
            imageVector = when {
                node.isDirectory && expanded -> Icons.Filled.KeyboardArrowDown
                node.isDirectory -> Icons.Filled.KeyboardArrowRight
                else -> Icons.Filled.Description
            },
            contentDescription = null,
            tint = colors.textMuted,
            modifier = Modifier.size(IconSize.xs),
        )

        if (node.isDirectory && themed != null) {
            Image(themed, contentDescription = null, modifier = Modifier.padding(start = Spacing.xxs).size(IconSize.xs))
        } else if (node.isDirectory) {
            Icon(
                imageVector = if (expanded) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                contentDescription = null,
                // An open folder takes the accent, so the path to the open file is traceable.
                tint = if (expanded) colors.accent else colors.textMuted,
                modifier = Modifier.padding(start = Spacing.xxs).size(IconSize.xs),
            )
        }

        Text(
            text = node.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) colors.listSelectionText else colors.plainText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Spacing.s),
        )
    }
}

/** One thin vertical rule per ancestor level, as VS Code draws nesting. */
@Composable
private fun IndentGuides(depth: Int) {
    val colors = editorColors
    Row {
        repeat(depth) {
            Box(
                modifier = Modifier
                    .width(Spacing.m)
                    .fillMaxHeight()
                    .padding(start = GUIDE_INSET),
            ) {
                Box(
                    modifier = Modifier
                        .width(Stroke.hairline)
                        .fillMaxHeight()
                        .background(colors.indentGuide),
                )
            }
        }
        Box(modifier = Modifier.width(Spacing.xs))
    }
}

/**
 * Guide offset inside one indent step: the base inset plus half the chevron
 * glyph, so each guide sits under its parent's chevron rather than beside it.
 */
private val GUIDE_INSET = Spacing.xs + IconSize.xs / 2
