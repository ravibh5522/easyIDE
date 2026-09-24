package dev.easyide.app.ui.screens.workspace.git

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import dev.easyide.app.R
import dev.easyide.app.ui.screens.workspace.ChromeButton
import dev.easyide.app.ui.screens.workspace.ChromeButtonStyle
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.sandbox.git.GitRemoteInfo
import dev.easyide.sandbox.git.GitStashEntry
import dev.easyide.sandbox.git.GitStatus
import dev.easyide.sandbox.git.isValidRemoteName

/** Remotes of the repository, with add and remove. Fetching from a new remote is the user's next tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteSheet(remotes: List<GitRemoteInfo>, remote: GitRemoteController, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(if (remotes.isEmpty()) GitRemotePlanner.DEFAULT_REMOTE else "") }
    var url by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = close, sheetMaxWidth = GitUi.sheetMaxWidth) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item { ListHeader(R.string.git_remotes_title) }
            if (remotes.isEmpty()) item { EmptyLine(R.string.git_no_remote) }
            items(remotes, key = { it.name }) { info ->
                Row(
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().padding(start = Spacing.l, end = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(info.name, style = MaterialTheme.typography.bodyMedium, color = editorColors.plainText)
                        Text(
                            info.url,
                            style = MaterialTheme.typography.labelSmall,
                            color = editorColors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { remote.requestRemoveRemote(info.name) }, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.git_remove_remote_cd, info.name), modifier = Modifier.size(IconSize.m))
                    }
                }
            }
            item {
                Column(modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.git_remote_name)) },
                        singleLine = true,
                        isError = name.isNotBlank() && !isValidRemoteName(name.trim()),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.git_remote_url)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                    )
                    ChromeButton(
                        text = stringResource(R.string.git_remote_add),
                        onClick = { remote.addRemote(name, url); url = ""; name = "" },
                        enabled = isValidRemoteName(name.trim()) && url.isNotBlank(),
                        style = ChromeButtonStyle.PRIMARY,
                        modifier = Modifier.padding(top = Spacing.s).minimumInteractiveComponentSize(),
                    )
                }
            }
        }
    }
}

/** Stash the working changes, and pop / apply / drop what was stashed before. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StashSheet(status: GitStatus?, stashes: List<GitStashEntry>, git: GitBranchController) {
    var message by rememberSaveable { mutableStateOf("") }
    var untracked by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = git::closeSheet, sheetMaxWidth = GitUi.sheetMaxWidth) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                Column(modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        label = { Text(stringResource(R.string.git_stash_message)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(untracked, role = Role.Checkbox, onValueChange = { untracked = it })
                            .minimumInteractiveComponentSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        Checkbox(checked = untracked, onCheckedChange = null)
                        Text(stringResource(R.string.git_stash_untracked), style = MaterialTheme.typography.bodyMedium)
                    }
                    ChromeButton(
                        text = stringResource(R.string.git_stash_push),
                        onClick = { git.stash(message.takeIf { it.isNotBlank() }, untracked); message = "" },
                        enabled = status?.isClean == false,
                        style = ChromeButtonStyle.PRIMARY,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    )
                }
            }
            item { ListHeader(R.string.git_stashes_title) }
            if (stashes.isEmpty()) item { EmptyLine(R.string.git_stashes_empty) }
            items(stashes, key = { it.index }) { entry -> StashRow(entry, git) }
        }
    }
}

@Composable
private fun StashRow(entry: GitStashEntry, git: GitBranchController) {
    val colors = editorColors
    Row(
        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().padding(start = Spacing.l, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.plainText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                DateUtils.getRelativeTimeSpanString(entry.timestampMillis).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
        TextButton(onClick = { git.popStash(entry.index) }, modifier = Modifier.minimumInteractiveComponentSize()) {
            Text(stringResource(R.string.git_stash_pop))
        }
        TextButton(onClick = { git.applyStash(entry.index) }, modifier = Modifier.minimumInteractiveComponentSize()) {
            Text(stringResource(R.string.git_stash_apply))
        }
        IconButton(onClick = { git.requestDropStash(entry.index) }, modifier = Modifier.minimumInteractiveComponentSize()) {
            Icon(Icons.Filled.Delete, stringResource(R.string.git_stash_drop_cd, entry.message), modifier = Modifier.size(IconSize.m))
        }
    }
}
