package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitToggle
import dev.easyide.app.ui.kit.ToggleKind
import dev.easyide.app.ui.screens.workspace.DialogHeading
import dev.easyide.app.ui.screens.workspace.DialogText
import dev.easyide.app.ui.screens.workspace.GitPanelState
import dev.easyide.app.ui.screens.workspace.SourceControlCallbacks
import dev.easyide.sandbox.git.GitIdentity
import dev.easyide.sandbox.git.GitStatus

/**
 * Message field, the amend row and a full-width commit button, in VS Code's order. Committing without an author
 * identity opens the identity form in place of a made-up author; the form's
 * save action commits, so the commit button waits until it is answered.
 */
@Composable
internal fun ScmCommitBox(state: GitPanelState, status: GitStatus, callbacks: SourceControlCallbacks) {
    val commit = callbacks.git.commit
    val canCommit = state.commitMessage.isNotBlank() &&
        (status.staged.isNotEmpty() || state.amend) && !state.busy
    val space = Kit.space

    KitField(
        value = state.commitMessage,
        onValueChange = callbacks.onMessageChanged,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Kit.control.hPad, vertical = space.xs),
        hint = stringResource(R.string.git_message_hint),
        singleLine = false,
        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )

    if (state.commits.isNotEmpty()) {
        KitRow(
            title = stringResource(R.string.git_amend),
            onClick = { commit.setAmend(!state.amend) },
            trailing = { KitToggle(state.amend, null, kind = ToggleKind.Check) },
        )
    }

    if (state.identityPrompt) {
        IdentityForm(
            failed = state.identitySaveFailed,
            onSave = commit::saveIdentity,
            onDismiss = commit::dismissIdentityPrompt,
        )
        return
    }

    KitButton(
        text = commitLabel(status, state.amend),
        onClick = callbacks.onCommit,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Kit.control.hPad, vertical = space.xs),
        icon = Icons.Filled.Check,
        enabled = canCommit,
        fillWidth = true,
    )
}

@Composable
private fun commitLabel(status: GitStatus, amend: Boolean): String = when {
    amend -> stringResource(R.string.git_commit_amend)
    status.staged.isEmpty() -> stringResource(R.string.git_commit)
    else -> pluralStringResource(R.plurals.git_commit_files, status.staged.size, status.staged.size)
}

/**
 * Asks who the commits are from, once. "This project only" writes the project
 * settings layer; otherwise the user's global settings, so the next project
 * needs no asking.
 */
@Composable
private fun IdentityForm(
    failed: Boolean,
    onSave: (name: String, email: String, projectOnly: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    val valid = GitIdentity.of(name, email) != null
    val space = Kit.space

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Kit.colors.raised)
            .padding(horizontal = Kit.control.hPad, vertical = space.s),
        verticalArrangement = Arrangement.spacedBy(space.s),
    ) {
        DialogHeading(stringResource(R.string.git_identity_title), Modifier.padding(top = space.none))
        DialogText(stringResource(R.string.git_identity_body), muted = true)
        KitField(name, { name = it }, Modifier.fillMaxWidth(), hint = stringResource(R.string.git_identity_name))
        KitField(
            email, { email = it }, Modifier.fillMaxWidth(),
            hint = stringResource(R.string.git_identity_email),
            error = if (failed) stringResource(R.string.git_identity_save_failed) else null,
            mono = true,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email),
        )
        KitButton(stringResource(R.string.git_identity_save_global), { onSave(name, email, false) }, enabled = valid)
        KitButton(stringResource(R.string.git_identity_save_project), { onSave(name, email, true) }, style = KitButtonStyle.Secondary, enabled = valid)
        KitButton(stringResource(R.string.git_cancel), onDismiss, style = KitButtonStyle.Ghost)
    }
}
