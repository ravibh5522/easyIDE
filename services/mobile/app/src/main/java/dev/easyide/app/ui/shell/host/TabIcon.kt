package dev.easyide.app.ui.shell.host

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import dev.easyide.app.ui.icons.iconFor
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.screens.workspace.files.FileIcon
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.workspace.FileDocuments

/**
 * The icon of a document tab: a file shows what the active file icon theme draws for its name (the same
 * glyph as the explorer), any other document its type's icon token in the muted text colour.
 */
@Composable
internal fun TabIcon(uri: DocumentUri, token: String) {
    val size = Kit.control.rowIcon
    if (FileDocuments.pathOf(uri) != null) FileIcon(uri.segments.last(), size = size)
    else Image(iconFor(token), null, Modifier.size(size), colorFilter = ColorFilter.tint(Kit.colors.textMuted))
}
