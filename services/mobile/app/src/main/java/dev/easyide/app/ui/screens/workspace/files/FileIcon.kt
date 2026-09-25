package dev.easyide.app.ui.screens.workspace.files

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Css
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Html
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Javascript
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Php
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import dev.easyide.app.ui.theme.EditorColors
import dev.easyide.app.ui.theme.ThemedIcon
import dev.easyide.app.ui.theme.rememberThemedFileIcon
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.editorColors

/**
 * The icon for a file, by name: a Material glyph tinted with a theme token, so it follows the
 * palette (light, dark, AMOLED, high contrast) with no colours of its own. Decorative - the
 * file name always sits next to it - so it carries no content description.
 *
 * With an icon theme active the theme's image is drawn instead, in the theme's own colours.
 * Used by the explorer; the editor tab strip and quick open take the same composable so a file
 * looks the same wherever it is listed.
 */
@Composable
fun FileIcon(name: String, modifier: Modifier = Modifier, size: Dp = IconSize.s) {
    // `workbench.iconTheme`: an active theme's own image replaces the built-in glyph everywhere a file is listed.
    when (val themed = rememberThemedFileIcon(name, isDirectory = false, expanded = false, size = size)) {
        is ThemedIcon.Ready -> Image(themed.image, null, modifier.size(size))
        ThemedIcon.Loading -> Spacer(modifier.size(size))
        ThemedIcon.None -> {
            val kind = FileKinds.of(name)
            Icon(
                imageVector = FileIcons.glyph(kind),
                contentDescription = null,
                tint = FileIcons.tint(kind, editorColors),
                modifier = modifier.size(size),
            )
        }
    }
}

/** Kind -> glyph and token tint, in one table so the mapping is reviewed as a whole. */
internal object FileIcons {

    fun glyph(kind: FileKind): ImageVector = when (kind) {
        FileKind.JAVASCRIPT -> Icons.Filled.Javascript
        FileKind.HTML -> Icons.Filled.Html
        FileKind.CSS -> Icons.Filled.Css
        FileKind.JVM -> Icons.Filled.Coffee
        FileKind.PHP -> Icons.Filled.Php
        FileKind.TYPESCRIPT, FileKind.PYTHON, FileKind.SYSTEMS, FileKind.SCRIPT_OTHER, FileKind.MARKUP -> Icons.Filled.Code
        FileKind.SHELL -> Icons.Filled.Terminal
        FileKind.JSON -> Icons.Filled.DataObject
        FileKind.CONFIG_DATA -> Icons.Filled.Tune
        FileKind.MARKDOWN, FileKind.LICENSE -> Icons.Filled.Article
        FileKind.IMAGE -> Icons.Filled.Image
        FileKind.VIDEO -> Icons.Filled.Movie
        FileKind.PDF -> Icons.Filled.PictureAsPdf
        FileKind.ARCHIVE -> Icons.Filled.FolderZip
        FileKind.DATABASE -> Icons.Filled.Storage
        FileKind.LOCK -> Icons.Filled.Lock
        FileKind.GIT -> Icons.Filled.CallSplit
        FileKind.ENVIRONMENT -> Icons.Filled.Key
        FileKind.BUILD -> Icons.Filled.Build
        FileKind.CONTAINER -> Icons.Filled.Inventory2
        FileKind.DOTFILE -> Icons.Filled.Settings
        FileKind.TEXT, FileKind.DEFAULT -> Icons.Filled.Description
    }

    /**
     * Tints come from the syntax and semantic tokens: languages and data borrow the colour a
     * reader already associates with that role, and chrome-like files (locks, dotfiles,
     * licences, plain text) stay muted so the eye lands on source files first.
     */
    fun tint(kind: FileKind, colors: EditorColors): Color = when (kind) {
        FileKind.JAVASCRIPT -> colors.syntax.number
        FileKind.TYPESCRIPT -> colors.syntax.function
        FileKind.HTML -> colors.syntax.tag
        FileKind.CSS -> colors.syntax.link
        FileKind.PYTHON -> colors.syntax.string
        FileKind.JVM -> colors.syntax.constant
        FileKind.PHP -> colors.syntax.keyword
        FileKind.SYSTEMS -> colors.syntax.type
        FileKind.SCRIPT_OTHER -> colors.syntax.variable
        FileKind.SHELL -> colors.success
        FileKind.JSON -> colors.syntax.constant
        FileKind.CONFIG_DATA -> colors.syntax.attribute
        FileKind.MARKUP -> colors.syntax.tag
        FileKind.MARKDOWN -> colors.syntax.heading
        FileKind.IMAGE -> colors.syntax.namespace
        FileKind.VIDEO -> colors.syntax.regexp
        FileKind.PDF -> colors.error
        FileKind.ARCHIVE -> colors.warning
        FileKind.DATABASE -> colors.info
        FileKind.GIT -> colors.git.modified
        FileKind.ENVIRONMENT -> colors.warning
        FileKind.BUILD -> colors.info
        FileKind.CONTAINER -> colors.info
        FileKind.LOCK, FileKind.LICENSE, FileKind.DOTFILE, FileKind.TEXT, FileKind.DEFAULT -> colors.textMuted
    }
}
