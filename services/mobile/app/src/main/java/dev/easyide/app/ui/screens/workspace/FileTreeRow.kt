package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.easyide.app.R
import dev.easyide.app.ui.screens.workspace.git.tint
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSizes
import dev.easyide.app.ui.kit.Twistie
import dev.easyide.app.ui.kit.kitPressPoint
import dev.easyide.app.ui.kit.rememberPressPoint
import dev.easyide.app.ui.screens.workspace.files.FileIcon
import dev.easyide.app.ui.screens.workspace.git.GitStatusLetter
import dev.easyide.app.ui.theme.ThemedIcon
import dev.easyide.app.ui.theme.rememberThemedFileIcon
import dev.easyide.sandbox.files.FileNode
import dev.easyide.sandbox.git.GitChangeType

/**
 * One explorer row, the kit row of every list: twistie, 16dp icon, name tinted by its git change, and at
 * the end a dot for unsaved edits and the git status letter. A folder that holds a change ([holdsChanges]) is tinted as modified. A long press and a right click both open the row's menu, under the
 * finger or pointer.
 */
@Composable
internal fun FileTreeRow(
    node: FileNode,
    depth: Int,
    expanded: Boolean,
    selected: Boolean,
    change: GitChangeType?,
    dirty: Boolean,
    holdsChanges: Boolean,
    onClick: () -> Unit,
    onMenu: (IntOffset) -> Unit,
) {
    val press = rememberPressPoint()
    // `workbench.iconTheme`: the theme's image replaces the built-in glyph when it has one.
    val icon = Kit.control.rowIcon
    val themed = rememberThemedFileIcon(node.name, node.isDirectory, expanded, icon)
    val colors = Kit.colors
    KitRow(
        title = node.name,
        modifier = Modifier
            .then(if (selected) Modifier.background(colors.listSelection) else Modifier)
            .treeGuides(depth)
            .kitPressPoint(press, onSecondary = onMenu),
        leading = {
            when {
                themed is ThemedIcon.Ready -> Image(themed.image, null, Modifier.size(icon))
                themed == ThemedIcon.Loading -> Spacer(Modifier.size(icon))
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
        titleColor = when {
            change != null -> change.tint(colors.git)
            holdsChanges -> GitChangeType.MODIFIED.tint(colors.git)
            else -> Color.Unspecified
        },
        trailing = if (change != null || dirty) ({ RowMarks(change, dirty) }) else null,
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

/** What sits at the end of a tree row: a dot while the file has unsaved edits, then its git status letter. */
@Composable
private fun RowMarks(change: GitChangeType?, dirty: Boolean) {
    val description = stringResource(R.string.gitui_modified_marker)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.s)) {
        if (dirty) {
            Box(Modifier.size(Kit.space.s).clip(FULLY_ROUND).background(Kit.colors.textMuted).semantics { contentDescription = description })
        }
        if (change != null) GitStatusLetter(change)
    }
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

/** Half the shorter side on every corner: a pill for a chip, a disc for a dot. */
private val FULLY_ROUND = RoundedCornerShape(percent = 50)
