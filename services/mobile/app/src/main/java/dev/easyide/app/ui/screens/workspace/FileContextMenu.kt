package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.extensions.adapters.MenuEntry
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.screens.workspace.ext.sections
import dev.easyide.sandbox.files.FileNode

/** What the explorer's long-press menu can do to a node. */
enum class FileAction {
    NEW_FILE,
    NEW_FOLDER,
    COPY,
    CUT,
    PASTE,
    RENAME,
    DELETE,
    COPY_PATH,
    COPY_RELATIVE_PATH,
}

/**
 * Long-press menu for a tree node.
 *
 * New file/folder appear only for directories (that is where they would be
 * created), and paste only when something is on the clipboard - a menu full of
 * entries that silently do nothing is worse than a short one.
 */
@Composable
fun FileContextMenu(
    node: FileNode?,
    canPaste: Boolean,
    onAction: (FileAction, FileNode) -> Unit,
    onDismiss: () -> Unit,
    /** Contributed `explorer/context` entries for [node] (already filtered by their `when`). */
    extensionEntries: (FileNode) -> List<MenuEntry> = { emptyList() },
    onExtensionEntry: (MenuEntry, FileNode) -> Unit = { _, _ -> },
) {
    if (node == null) return

    fun action(label: String, what: FileAction, danger: Boolean = false) =
        KitMenuItem.Action(label, { onAction(what, node) }, danger = danger)

    val items = buildList {
        if (node.isDirectory) {
            add(action(stringResource(R.string.wp_new_file), FileAction.NEW_FILE))
            add(action(stringResource(R.string.wp_new_folder), FileAction.NEW_FOLDER))
            add(KitMenuItem.Divider)
        }
        add(action(stringResource(R.string.wp_copy), FileAction.COPY))
        add(action(stringResource(R.string.wp_cut), FileAction.CUT))
        if (canPaste && node.isDirectory) add(action(stringResource(R.string.wp_paste), FileAction.PASTE))
        add(KitMenuItem.Divider)
        add(action(stringResource(R.string.wp_rename), FileAction.RENAME))
        add(action(stringResource(R.string.wp_delete), FileAction.DELETE, danger = true))
        add(KitMenuItem.Divider)
        add(action(stringResource(R.string.wp_copy_path), FileAction.COPY_PATH))
        add(action(stringResource(R.string.wp_copy_relative_path), FileAction.COPY_RELATIVE_PATH))
        extensionEntries(node).sections().forEach { section ->
            add(KitMenuItem.Divider)
            section.forEach { entry ->
                add(KitMenuItem.Action(entry.command.title, { onExtensionEntry(entry, node) }, enabled = entry.enabled))
            }
        }
    }
    KitMenu(expanded = true, onDismiss = onDismiss, items = items)
}

/** Shared prompt for the actions that need a name (new file/folder, rename). */
@Composable
fun NameInputDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    val submit = { onConfirm(value.trim()) }

    KitDialog(
        title = title,
        onDismiss = onDismiss,
        confirm = if (value.isBlank()) null else KitAction(confirmLabel, submit),
        dismiss = KitAction(stringResource(R.string.action_cancel), onDismiss),
    ) {
        KitField(
            value = value,
            onValueChange = { value = it },
            mono = true,
            keyboard = NAME_KEYBOARD,
            keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) submit() }),
        )
    }
}

/** A folder holds many files, so deleting one asks for its name; a single file asks once. */
internal fun deleteNeedsName(node: FileNode): Boolean = node.isDirectory

internal fun deleteConfirmed(node: FileNode, typed: String): Boolean = !deleteNeedsName(node) || typed.trim() == node.name

/** Deletion is irreversible here - there is no trash - so it is confirmed, and a folder by typing its name. */
@Composable
fun ConfirmDeleteDialog(
    node: FileNode,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    KitDialog(
        title = stringResource(R.string.wp_delete_title, node.name),
        onDismiss = onDismiss,
        tone = Tone.Danger,
        confirm = if (deleteConfirmed(node, typed)) KitAction(stringResource(R.string.wp_delete), onConfirm) else null,
        dismiss = KitAction(stringResource(R.string.action_cancel), onDismiss),
    ) {
        DialogText(stringResource(if (node.isDirectory) R.string.wp_delete_body_folder else R.string.wp_delete_body_file))
        if (deleteNeedsName(node)) {
            KitField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier.padding(top = Kit.space.m),
                label = stringResource(R.string.wp_delete_type_name, node.name),
                mono = true,
                keyboard = NAME_KEYBOARD,
            )
        }
    }
}
