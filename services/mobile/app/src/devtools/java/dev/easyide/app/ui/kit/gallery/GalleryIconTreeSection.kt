package dev.easyide.app.ui.kit.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.screens.workspace.files.FileIcon
import dev.easyide.app.ui.theme.ThemedIcon
import dev.easyide.app.ui.theme.rememberThemedFileIcon

private val TREE_SIZES = listOf(16.dp, 24.dp)

/** One row of the sample tree: a name, its depth, and whether it is a folder (expanded folders show their open icon). */
private class TreeRow(val name: String, val depth: Int, val folder: Boolean = false, val open: Boolean = false)

/** A small project as the explorer would list it, so the owner can check the real-world names at a glance. */
private val SAMPLE_TREE = listOf(
    TreeRow("myapp", 0, folder = true, open = true),
    TreeRow(".github", 1, folder = true, open = true),
    TreeRow("ci.yml", 2),
    TreeRow("src", 1, folder = true, open = true),
    TreeRow("main.py", 2),
    TreeRow("index.ts", 2),
    TreeRow("style.css", 2),
    TreeRow("components", 1, folder = true),
    TreeRow("tests", 1, folder = true),
    TreeRow("docs", 1, folder = true),
    TreeRow("node_modules", 1, folder = true),
    TreeRow("Dockerfile", 1),
    TreeRow("pyproject.toml", 1),
    TreeRow("package.json", 1),
    TreeRow("README.md", 1),
    TreeRow(".gitignore", 1),
    TreeRow("Makefile", 1),
    TreeRow("build.gradle.kts", 1),
    TreeRow("LICENSE", 1),
    TreeRow("logo.svg", 1),
)

private val INDENT = 12.dp

/** The active icon theme on a realistic project tree at 16dp and 24dp; the golden of the Material Icon Theme captures it. */
@Composable
fun IconTreeSection() {
    KitSection(stringResource(R.string.gallery_icon_tree_title), description = stringResource(R.string.gallery_icon_tree_note)) {
        Row(Modifier.padding(Kit.space.m), horizontalArrangement = Arrangement.spacedBy(Kit.space.xl)) {
            for (size in TREE_SIZES) Column(verticalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
                for (row in SAMPLE_TREE) TreeLine(row, size)
            }
        }
    }
}

@Composable
private fun TreeLine(row: TreeRow, size: Dp) {
    Row(Modifier.padding(start = INDENT * row.depth), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.s)) {
        if (row.folder) {
            when (val icon = rememberThemedFileIcon(row.name, isDirectory = true, expanded = row.open, size = size)) {
                is ThemedIcon.Ready -> Image(icon.image, null, Modifier.size(size))
                else -> Spacer(Modifier.size(size))
            }
        } else {
            FileIcon(row.name, size = size)
        }
        BasicText(row.name, style = Kit.text.body.copy(color = Kit.colors.plainText), maxLines = 1)
    }
}
