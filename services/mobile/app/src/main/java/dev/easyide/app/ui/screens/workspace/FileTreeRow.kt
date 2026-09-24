package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.IntOffset
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSizes
import dev.easyide.app.ui.kit.Twistie
import dev.easyide.app.ui.kit.kitPressPoint
import dev.easyide.app.ui.kit.rememberPressPoint
import dev.easyide.app.ui.screens.workspace.files.FileIcon
import dev.easyide.app.ui.screens.workspace.git.GitStatusLetter
import dev.easyide.app.ui.theme.rememberThemedFileIcon
import dev.easyide.sandbox.files.FileNode
import dev.easyide.sandbox.git.GitChangeType

/**
 * One explorer row, the kit row of every list: twistie, 16dp icon, name, and the git status letter at
 * the end when the file has a change. A long press and a right click both open the row's menu, under the
 * finger or pointer.
 */
@Composable
internal fun FileTreeRow(
    node: FileNode,
    depth: Int,
    expanded: Boolean,
    selected: Boolean,
    change: GitChangeType?,
    onClick: () -> Unit,
    onMenu: (IntOffset) -> Unit,
) {
    val press = rememberPressPoint()
    // `workbench.iconTheme`: the theme's image replaces the built-in glyph when it has one.
    val themed = rememberThemedFileIcon(node.name, node.isDirectory, expanded)
    val icon = Kit.control.rowIcon
    val colors = Kit.colors
    KitRow(
        title = node.name,
        modifier = Modifier
            .then(if (selected) Modifier.background(colors.listSelection) else Modifier)
            .treeGuides(depth)
            .kitPressPoint(press, onSecondary = onMenu),
        leading = {
            when {
                themed != null -> Image(themed, null, Modifier.size(icon))
                !node.isDirectory -> FileIcon(node.name, size = icon)
                // An open folder takes the accent, so the path to the open file is traceable.
                else -> Image(
                    if (expanded) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                    null,
                    Modifier.size(icon),
                    colorFilter = ColorFilter.tint(if (expanded) colors.accent else colors.textMuted),
                )
            }
        },
        trailing = if (change != null) ({ GitStatusLetter(change) }) else null,
        onClick = onClick,
        selected = selected,
        onLongClick = { onMenu(press.at) },
        level = depth,
        twistie = when {
            !node.isDirectory -> Twistie.Leaf
            expanded -> Twistie.Expanded
            else -> Twistie.Collapsed
        },
    )
}

/** One hairline per ancestor level, under that ancestor's twistie, as VS Code draws nesting. */
@Composable
internal fun Modifier.treeGuides(depth: Int): Modifier {
    if (depth == 0) return this
    val color = Kit.colors.indentGuide
    val first = Kit.control.hPad + KitSizes.twistieSlot / 2
    val step = Kit.control.indent
    val width = Kit.hairline
    return drawBehind {
        repeat(depth) { level -> drawRect(color, Offset((first + step * level).toPx(), 0f), Size(width.toPx(), size.height)) }
    }
}
