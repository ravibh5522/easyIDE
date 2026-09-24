package dev.easyide.app.ui.shell.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.easyide.app.extensions.adapters.ShellContributions
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.shell.ext.ExtIcons
import dev.easyide.app.ui.shell.IconRef

/**
 * The Material stand-ins for the glyph names of [CoreShell][dev.easyide.app.ui.shell.CoreShell] until the
 * custom icon set (R5) replaces them. A name this table does not know, such as a pack's `ext:` vector,
 * gets [FALLBACK] rather than nothing, so an extension item is always tappable.
 */
object NavIcons {
    private val TABLE: Map<String, ImageVector> = mapOf(
        "home" to Icons.Filled.Home,
        "extensions" to Icons.Filled.Extension,
        "settings" to Icons.Filled.Settings,
        "files" to Icons.Filled.Folder,
        "search" to Icons.Filled.Search,
        "git" to Icons.Filled.Difference,
        "problems" to Icons.Filled.ErrorOutline,
        "terminal" to Icons.Filled.Terminal,
        "outline" to Icons.Filled.AccountTree,
        "output" to Icons.Filled.Description,
        "warning" to Icons.Filled.Warning,
        "commands" to Icons.Filled.Keyboard,
        "back" to Icons.AutoMirrored.Filled.ArrowBack,
        "close" to Icons.Filled.Close,
    )

    private val FALLBACK = Icons.Filled.Widgets

    val more: ImageVector = Icons.Filled.MoreHoriz

    fun of(ref: IconRef): ImageVector = TABLE[ref.name] ?: FALLBACK

    /** [of], except that a pack's own SVG (`ext:` names) is drawn by [ExtIcons] once it has been read. */
    @Composable
    fun icon(ref: IconRef): ImageVector =
        if (ref.name.startsWith(ShellContributions.EXT_ICON_PREFIX)) ExtIcons.of(ref, Kit.colors.plainText) else of(ref)
}
