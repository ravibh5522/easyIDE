package dev.easyide.app.ui.screens.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import dev.easyide.app.R

/**
 * What can be done to one project. [onOpenLocation] is null when the project has
 * no linked external folder: its files live in app storage, which no file
 * manager can browse, so there is nowhere to open.
 */
internal class ProjectMenuActions(
    val onOpen: () -> Unit,
    val onRename: () -> Unit,
    val onDuplicate: () -> Unit,
    val onChangeEnvironment: () -> Unit,
    val onOpenLocation: (() -> Unit)?,
    val onDelete: () -> Unit,
)

/** The long-press / right-click menu, also behind the detail pane's "more" button. */
@Composable
internal fun ProjectContextMenu(expanded: Boolean, onDismiss: () -> Unit, actions: ProjectMenuActions) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuItem(R.string.home_menu_open, Icons.Filled.OpenInNew, onDismiss, actions.onOpen)
        MenuItem(R.string.home_menu_rename, Icons.Filled.Edit, onDismiss, actions.onRename)
        MenuItem(R.string.home_menu_duplicate, Icons.Filled.ContentCopy, onDismiss, actions.onDuplicate)
        MenuItem(R.string.home_menu_change_environment, Icons.Filled.SwapHoriz, onDismiss, actions.onChangeEnvironment)
        actions.onOpenLocation?.let { open ->
            MenuItem(R.string.home_menu_open_location, Icons.Filled.FolderOpen, onDismiss, open)
        }
        HorizontalDivider()
        MenuItem(R.string.home_menu_delete, Icons.Filled.Delete, onDismiss, actions.onDelete, destructive = true)
    }
}

@Composable
private fun MenuItem(
    label: Int,
    icon: ImageVector,
    onDismiss: () -> Unit,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(stringResource(label), color = color) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color) },
        onClick = {
            onDismiss()
            onClick()
        },
    )
}
