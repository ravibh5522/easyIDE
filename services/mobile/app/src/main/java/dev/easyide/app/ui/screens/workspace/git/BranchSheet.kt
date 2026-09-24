package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitGroup
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.screens.workspace.DialogHeading
import dev.easyide.app.ui.screens.workspace.DialogText
import dev.easyide.app.ui.screens.workspace.NAME_KEYBOARD
import dev.easyide.sandbox.git.GitBranch
import dev.easyide.sandbox.git.isValidBranchName

/**
 * List, create, switch, rename and delete branches; remote-tracking ones switch by creating a local copy.
 * The new branch's name and start point sit above the lists, and "Create and switch" is the sheet's action.
 */
@Composable
internal fun BranchSheet(branches: List<GitBranch>, git: GitBranchController) {
    var renaming by remember { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val local = branches.filterNot { it.isRemote }
    val remote = branches.filter { it.isRemote }
    val valid = isValidBranchName(name.trim())

    KitDialog(
        title = stringResource(R.string.wp_git_branches_title),
        onDismiss = git::closeSheet,
        confirm = if (valid) KitAction(stringResource(R.string.git_create_branch)) { git.create(name.trim(), start); name = "" } else null,
        dismiss = KitAction(stringResource(R.string.wp_close), git::closeSheet),
    ) {
        KitField(
            value = name,
            onValueChange = { name = it },
            label = stringResource(R.string.git_new_branch),
            error = if (name.isNotBlank() && !valid) stringResource(R.string.wp_git_invalid_name) else null,
            mono = true,
            keyboard = NAME_KEYBOARD,
        )
        // An inline picker, not a menu: choosers inside a dialog are lists (U-CMP-07).
        KitGroup(Modifier.padding(top = Kit.space.s)) {
            KitRow(
                title = stringResource(R.string.git_branch_from, start ?: stringResource(R.string.git_branch_from_head)),
                mono = true,
                onClick = { picking = !picking },
            )
            if (picking) StartPoints(branches, start) { start = it; picking = false }
        }
        if (branches.isEmpty()) DialogText(stringResource(R.string.git_branches_empty), Modifier.padding(top = Kit.space.m), muted = true)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = GitUi.sheetListMaxHeight)) {
            if (local.isNotEmpty()) item { DialogHeading(stringResource(R.string.git_branches_local)) }
            items(local, key = { "l:${it.name}" }) { branch ->
                BranchRow(branch, { git.switchTo(branch.name) }, { renaming = branch.name }, { git.requestDelete(branch.name) })
            }
            if (remote.isNotEmpty()) item { DialogHeading(stringResource(R.string.git_branches_remote)) }
            items(remote, key = { "r:${it.name}" }) { branch ->
                BranchRow(branch, { git.switchTo(branch.name) }, onRename = null, onDelete = null)
            }
        }
    }

    renaming?.let { old ->
        GitNameDialog(
            title = stringResource(R.string.git_rename_branch_title, old),
            initial = old,
            label = stringResource(R.string.git_branch_name),
            confirmLabel = stringResource(R.string.git_rename),
            isValid = { it != old && isValidBranchName(it) },
            onConfirm = { git.rename(old, it) },
            onDismiss = { renaming = null },
        )
    }
}

/** The current commit first, then every branch, as radio-like rows; the chosen one carries the block marker. */
@Composable
private fun StartPoints(branches: List<GitBranch>, start: String?, onPick: (String?) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = GitUi.sheetListMaxHeight)) {
        item { KitRow(stringResource(R.string.git_branch_from_head), selected = start == null, mono = true, onClick = { onPick(null) }) }
        items(branches, key = { "p:${it.name}" }) { branch ->
            KitRow(branch.name, selected = start == branch.name, mono = true, onClick = { onPick(branch.name) })
        }
    }
}

/** The row switches (the current one is not a target); rename and delete are its two trailing actions, local branches only. */
@Composable
private fun BranchRow(branch: GitBranch, onSwitch: () -> Unit, onRename: (() -> Unit)?, onDelete: (() -> Unit)?) {
    KitRow(
        title = branch.name,
        subtitle = branch.upstream?.let { stringResource(R.string.git_branch_tracks, it) } ?: branch.shortId,
        mono = true,
        selected = branch.isCurrent,
        onClick = if (branch.isCurrent) null else onSwitch,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                onRename?.let { KitIconButton(Icons.Filled.Edit, stringResource(R.string.git_rename_branch_cd, branch.name), it) }
                onDelete?.let { KitIconButton(Icons.Filled.Delete, stringResource(R.string.git_delete_branch_cd, branch.name), it, enabled = !branch.isCurrent) }
            }
        },
    )
}
