package dev.easyide.app.ui.screens.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.KitMenuItem

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

/** The long-press / right-click menu of a row, also behind the project page's "..." button. */
@Composable
internal fun projectMenuItems(actions: ProjectMenuActions): List<KitMenuItem> = buildList {
    add(KitMenuItem.Action(stringResource(R.string.home_menu_open), actions.onOpen, Icons.Filled.OpenInNew))
    add(KitMenuItem.Action(stringResource(R.string.home_menu_rename), actions.onRename, Icons.Filled.Edit))
    add(KitMenuItem.Action(stringResource(R.string.home_menu_duplicate), actions.onDuplicate, Icons.Filled.ContentCopy))
    add(KitMenuItem.Action(stringResource(R.string.home_menu_change_environment), actions.onChangeEnvironment, Icons.Filled.SwapHoriz))
    actions.onOpenLocation?.let { open ->
        add(KitMenuItem.Action(stringResource(R.string.home_menu_open_location), open, Icons.Filled.FolderOpen))
    }
    add(KitMenuItem.Divider)
    add(KitMenuItem.Action(stringResource(R.string.home_menu_delete), actions.onDelete, Icons.Filled.Delete, danger = true))
}

/** The menu actions of any project, wired to the screen's callbacks; rows and the project page share it. */
@Composable
internal fun rememberProjectActions(callbacks: HomeCallbacks): (ProjectListItem) -> ProjectMenuActions {
    val context = LocalContext.current
    return remember(callbacks, context) {
        { item ->
            val id = item.project.id
            ProjectMenuActions(
                onOpen = { callbacks.onOpenProject(item, false) },
                onRename = { callbacks.onDialog(HomeDialog.Rename(id)) },
                onDuplicate = { callbacks.onDialog(HomeDialog.Duplicate(id)) },
                onChangeEnvironment = { callbacks.onDialog(HomeDialog.ChangeEnvironment(id)) },
                onOpenLocation = item.project.externalFolderUri?.let { uri -> { openFolderLocation(context, uri) } },
                onDelete = { callbacks.onDialog(HomeDialog.Delete(id)) },
            )
        }
    }
}

/**
 * Shows the linked folder in the system file manager. The intent may have no
 * handler on a locked-down device; that is a platform boundary, and there is
 * nothing to fall back to, so it is a no-op rather than a crash.
 */
private fun openFolderLocation(context: Context, treeUri: String) {
    val tree = Uri.parse(treeUri)
    val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(document, DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No file manager installed that can show a folder.
    }
}
