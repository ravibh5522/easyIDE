package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.screens.workspace.ChromeButton
import dev.easyide.app.ui.screens.workspace.ChromeButtonStyle
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.app.ui.theme.sectionHeader
import dev.easyide.sandbox.git.GitBranch
import dev.easyide.sandbox.git.isValidBranchName

/** List, create, switch, rename and delete branches; remote-tracking ones switch by creating a local copy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BranchSheet(branches: List<GitBranch>, git: GitBranchController) {
    var renaming by remember { mutableStateOf<String?>(null) }
    val local = branches.filterNot { it.isRemote }
    val remote = branches.filter { it.isRemote }

    ModalBottomSheet(onDismissRequest = git::closeSheet, sheetMaxWidth = GitUi.sheetMaxWidth) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item { CreateBranchForm(branches, onCreate = git::create) }
            if (branches.isEmpty()) {
                item { EmptyLine(R.string.git_branches_empty) }
            }
            if (local.isNotEmpty()) item { ListHeader(R.string.git_branches_local) }
            items(local, key = { "l:${it.name}" }) { branch ->
                BranchRow(
                    branch = branch,
                    onSwitch = { git.switchTo(branch.name) },
                    onRename = { renaming = branch.name },
                    onDelete = { git.requestDelete(branch.name) },
                )
            }
            if (remote.isNotEmpty()) item { ListHeader(R.string.git_branches_remote) }
            items(remote, key = { "r:${it.name}" }) { branch ->
                BranchRow(branch, onSwitch = { git.switchTo(branch.name) }, onRename = null, onDelete = null)
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

@Composable
private fun CreateBranchForm(branches: List<GitBranch>, onCreate: (name: String, startPoint: String?) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.git_new_branch)) },
            singleLine = true,
            isError = name.isNotBlank() && !isValidBranchName(name.trim()),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Box(modifier = Modifier.weight(1f)) {
                TextButton(onClick = { menu = true }, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Text(
                        text = stringResource(R.string.git_branch_from, start ?: stringResource(R.string.git_branch_from_head)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.git_branch_from_head)) },
                        onClick = { start = null; menu = false },
                    )
                    branches.forEach { branch ->
                        DropdownMenuItem(text = { Text(branch.name) }, onClick = { start = branch.name; menu = false })
                    }
                }
            }
            ChromeButton(
                text = stringResource(R.string.git_create_branch),
                onClick = { onCreate(name.trim(), start); name = "" },
                enabled = isValidBranchName(name.trim()),
                style = ChromeButtonStyle.PRIMARY,
                modifier = Modifier.minimumInteractiveComponentSize(),
            )
        }
    }
}

@Composable
private fun BranchRow(
    branch: GitBranch,
    onSwitch: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val colors = editorColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !branch.isCurrent, role = Role.Button, onClick = onSwitch)
            .minimumInteractiveComponentSize()
            .padding(start = Spacing.l, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = branch.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (branch.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = colors.plainText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = branch.upstream?.let { stringResource(R.string.git_branch_tracks, it) } ?: branch.shortId,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
        if (branch.isCurrent) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.git_branch_current),
                tint = colors.accent,
                modifier = Modifier.padding(end = Spacing.m).size(IconSize.m),
            )
        }
        onRename?.let {
            IconButton(onClick = it, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(Icons.Filled.Edit, stringResource(R.string.git_rename_branch_cd, branch.name), modifier = Modifier.size(IconSize.m))
            }
        }
        onDelete?.let {
            IconButton(onClick = it, enabled = !branch.isCurrent, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(Icons.Filled.Delete, stringResource(R.string.git_delete_branch_cd, branch.name), modifier = Modifier.size(IconSize.m))
            }
        }
    }
}

@Composable
internal fun ListHeader(text: Int) {
    Text(
        text = stringResource(text).uppercase(),
        style = MaterialTheme.typography.sectionHeader,
        color = editorColors.textMuted,
        modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.m, bottom = Spacing.xs),
    )
}

@Composable
internal fun EmptyLine(text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = editorColors.textMuted,
        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
    )
}
