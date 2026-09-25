package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.screens.workspace.files.FileIcon
import dev.easyide.app.ui.theme.ThemedIcon
import dev.easyide.app.ui.theme.rememberThemedFileIcon

/** Names the preview strip resolves through the active icon theme: a spread of languages, data and media. */
private val PREVIEW_FILES = listOf(
    "Main.kt", "index.ts", "app.py", "lib.rs", "main.go", "package.json", "Dockerfile", "README.md",
    "style.css", "logo.png", "data.csv", "archive.zip",
)

private val PREVIEW_FOLDERS = listOf("src", "test", "docs", "node_modules")

/** A strip of a few file and folder icons drawn by the active icon theme, under the picker row. */
@Composable
internal fun IconThemePreview() {
    val size = Kit.control.rowIcon
    Row(
        Modifier.padding(horizontal = Kit.space.l, vertical = Kit.space.s),
        horizontalArrangement = Arrangement.spacedBy(Kit.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (folder in PREVIEW_FOLDERS) FolderPreview(folder, size)
        for (file in PREVIEW_FILES) FileIcon(file, size = size)
    }
}

@Composable
private fun FolderPreview(name: String, size: androidx.compose.ui.unit.Dp) {
    when (val icon = rememberThemedFileIcon(name, isDirectory = true, expanded = false, size = size)) {
        is ThemedIcon.Ready -> Image(icon.image, null, Modifier.size(size))
        else -> Spacer(Modifier.size(size))
    }
}
