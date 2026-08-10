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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.theme.editorColors
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
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = projectName.uppercase().ifEmpty { "EXPLORER" },
            style = MaterialTheme.typography.labelSmall,
            color = colors.gutterText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        HeaderAction(Icons.Filled.NoteAdd, "New file", onNewFile)
        HeaderAction(Icons.Filled.CreateNewFolder, "New folder", onNewFolder)
        HeaderAction(Icons.Filled.Refresh, "Refresh", onRefresh)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val colors = editorColors
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = colors.gutterText,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(HEADER_ICON_DP.dp)
            .combinedClickable(onClick = onClick),
    )
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
            .background(if (selected) colors.tabActive else Color.Transparent)
            // Fixed height so the indent guides have a bounded height to fill.
            .height(ROW_HEIGHT_DP.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IndentGuides(depth)

        Icon(
            imageVector = when {
                node.isDirectory && expanded -> Icons.Filled.KeyboardArrowDown
                node.isDirectory -> Icons.Filled.KeyboardArrowRight
                else -> Icons.Filled.Description
            },
            contentDescription = null,
            tint = colors.gutterText,
            modifier = Modifier.size(CHEVRON_DP.dp),
        )

        if (node.isDirectory) {
            Icon(
                imageVector = if (expanded) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                contentDescription = null,
                tint = colors.gutterText,
                modifier = Modifier.padding(start = 2.dp).size(ICON_DP.dp),
            )
        }

        Text(
            text = node.name,
            style = MaterialTheme.typography.bodySmall,
            color = colors.plainText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
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
                    .width(INDENT_DP.dp)
                    .fillMaxHeight()
                    .padding(start = GUIDE_INSET_DP.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(GUIDE_WIDTH_DP.dp)
                        .fillMaxHeight()
                        .background(colors.panelBorder),
                )
            }
        }
        Box(modifier = Modifier.width(BASE_INSET_DP.dp))
    }
}

private const val INDENT_DP = 12
private const val BASE_INSET_DP = 6
private const val GUIDE_INSET_DP = 5
private const val GUIDE_WIDTH_DP = 1
private const val ROW_HEIGHT_DP = 26
private const val ICON_DP = 14
private const val CHEVRON_DP = 14
private const val HEADER_ICON_DP = 16
