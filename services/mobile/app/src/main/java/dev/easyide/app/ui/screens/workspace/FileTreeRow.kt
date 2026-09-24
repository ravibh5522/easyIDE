package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.collectFlags
import dev.easyide.app.ui.kit.kitFocusRing
import dev.easyide.app.ui.kit.kitMarker
import dev.easyide.app.ui.kit.kitStateLayer
import dev.easyide.app.ui.screens.workspace.files.FileIcon
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.rememberThemedFileIcon
import dev.easyide.sandbox.files.FileNode

/** One explorer row: indent guides, chevron and icon for a folder, the file icon otherwise, then the name. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileTreeRow(
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
