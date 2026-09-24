package dev.easyide.app.ui.screens.workspace.git

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitToggle
import dev.easyide.app.ui.kit.ToggleKind
import dev.easyide.app.ui.screens.workspace.DialogHeading
import dev.easyide.app.ui.screens.workspace.DialogText
import dev.easyide.app.ui.screens.workspace.NAME_KEYBOARD
import dev.easyide.app.ui.screens.workspace.URL_KEYBOARD
import dev.easyide.sandbox.git.GitRemoteInfo
import dev.easyide.sandbox.git.GitStashEntry
import dev.easyide.sandbox.git.GitStatus
import dev.easyide.sandbox.git.isValidRemoteName

/** Remotes of the repository, with add and remove. Fetching from a new remote is the user's next tap. */
@Composable
internal fun RemoteSheet(remotes: List<GitRemoteInfo>, remote: GitRemoteController, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(if (remotes.isEmpty()) GitRemotePlanner.DEFAULT_REMOTE else "") }
    var url by rememberSaveable { mutableStateOf("") }
    val valid = isValidRemoteName(name.trim()) && url.isNotBlank()

    KitDialog(
        title = stringResource(R.string.git_remotes_title),
        onDismiss = close,
        confirm = if (valid) KitAction(stringResource(R.string.git_remote_add)) { remote.addRemote(name, url); url = ""; name = "" } else null,
        dismiss = KitAction(stringResource(R.string.wp_close), close),
    ) {
        if (remotes.isEmpty()) DialogText(stringResource(R.string.git_no_remote), muted = true)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = GitUi.sheetListMaxHeight)) {
            items(remotes, key = { it.name }) { info ->
                KitRow(
                    title = info.name,
                    subtitle = info.url,
                    mono = true,
                    trailing = { KitIconButton(Icons.Filled.Delete, stringResource(R.string.git_remove_remote_cd, info.name), { remote.requestRemoveRemote(info.name) }) },
                )
            }
        }
        KitField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.padding(top = Kit.space.m),
            label = stringResource(R.string.git_remote_name),
            error = if (name.isNotBlank() && !isValidRemoteName(name.trim())) stringResource(R.string.wp_git_invalid_name) else null,
            mono = true,
            keyboard = NAME_KEYBOARD,
        )
        KitField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.padding(top = Kit.space.s),
            label = stringResource(R.string.git_remote_url),
            mono = true,
            keyboard = URL_KEYBOARD,
        )
    }
}

/** Stash the working changes, and pop / apply / drop what was stashed before. */
@Composable
internal fun StashSheet(status: GitStatus?, stashes: List<GitStashEntry>, git: GitBranchController) {
    var message by rememberSaveable { mutableStateOf("") }
    var untracked by rememberSaveable { mutableStateOf(false) }

    KitDialog(
        title = stringResource(R.string.git_stashes_title),
        onDismiss = git::closeSheet,
        confirm = if (status?.isClean == false) {
            KitAction(stringResource(R.string.git_stash_push)) { git.stash(message.takeIf { it.isNotBlank() }, untracked); message = "" }
        } else null,
        dismiss = KitAction(stringResource(R.string.wp_close), git::closeSheet),
    ) {
        KitField(message, { message = it }, label = stringResource(R.string.git_stash_message))
        KitRow(
            title = stringResource(R.string.git_stash_untracked),
            onClick = { untracked = !untracked },
            trailing = { KitToggle(untracked, null, kind = ToggleKind.Check) },
        )
        DialogHeading(stringResource(R.string.git_stashes_title))
        if (stashes.isEmpty()) DialogText(stringResource(R.string.git_stashes_empty), muted = true)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = GitUi.sheetListMaxHeight)) {
            items(stashes, key = { it.index }) { entry -> StashRow(entry, git) }
        }
    }
}

/** The entry with its three actions on a line of their own: pop, apply and drop do not fit beside a message. */
@Composable
private fun StashRow(entry: GitStashEntry, git: GitBranchController) {
    Column {
        KitRow(
            title = entry.message,
            subtitle = DateUtils.getRelativeTimeSpanString(entry.timestampMillis).toString(),
        )
        Row(Modifier.padding(horizontal = Kit.space.s), horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
            KitButton(stringResource(R.string.git_stash_pop), { git.popStash(entry.index) }, style = KitButtonStyle.Ghost)
            KitButton(stringResource(R.string.git_stash_apply), { git.applyStash(entry.index) }, style = KitButtonStyle.Ghost)
            KitButton(stringResource(R.string.git_stash_drop), { git.requestDropStash(entry.index) }, style = KitButtonStyle.Danger)
        }
    }
}
