package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.easyide.app.R
import dev.easyide.app.ui.screens.workspace.ChromeButton
import dev.easyide.app.ui.screens.workspace.ChromeButtonStyle
import dev.easyide.app.ui.screens.workspace.DenseTextField
import dev.easyide.app.ui.screens.workspace.GitPanelState
import dev.easyide.app.ui.screens.workspace.SourceControlCallbacks
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.sandbox.git.GitIdentity
import dev.easyide.sandbox.git.GitStatus

/**
 * Message box, amend toggle and commit button. Committing without an author
 * identity opens the identity form in place of a made-up author.
 */
@Composable
internal fun ScmCommitBox(state: GitPanelState, status: GitStatus, callbacks: SourceControlCallbacks) {
    val commit = callbacks.git.commit
    val canCommit = state.commitMessage.isNotBlank() &&
        (status.staged.isNotEmpty() || state.amend) && !state.busy

    DenseTextField(
        value = state.commitMessage,
        onValueChange = callbacks.onMessageChanged,
        placeholder = stringResource(R.string.git_message_hint),
        maxLines = COMMIT_MESSAGE_MAX_LINES,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs),
    )

    if (state.commits.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(state.amend, role = Role.Checkbox, onValueChange = commit::setAmend)
                .minimumInteractiveComponentSize()
                .padding(horizontal = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Checkbox(checked = state.amend, onCheckedChange = null)
            Text(
                text = stringResource(R.string.git_amend),
                style = MaterialTheme.typography.bodySmall,
                color = editorColors.plainText,
            )
        }
    }

    if (state.identityPrompt) {
        IdentityForm(
            failed = state.identitySaveFailed,
            onSave = commit::saveIdentity,
            onDismiss = commit::dismissIdentityPrompt,
        )
    }

    ChromeButton(
        text = commitLabel(status, state.amend),
        onClick = callbacks.onCommit,
        enabled = canCommit,
        style = ChromeButtonStyle.PRIMARY,
        icon = Icons.Filled.Check,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.xs)
            .minimumInteractiveComponentSize(),
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
    val colors = editorColors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.raised)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.git_identity_title),
            style = MaterialTheme.typography.labelLarge,
            color = colors.plainText,
        )
        Text(
            text = stringResource(R.string.git_identity_body),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textMuted,
        )
        DenseTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(R.string.git_identity_name),
            modifier = Modifier.fillMaxWidth(),
        )
        DenseTextField(
            value = email,
            onValueChange = { email = it },
            placeholder = stringResource(R.string.git_identity_email),
            modifier = Modifier.fillMaxWidth(),
        )
        if (failed) {
            Text(
                text = stringResource(R.string.git_identity_save_failed),
                style = MaterialTheme.typography.labelSmall,
                color = colors.error,
            )
        }
        ChromeButton(
            text = stringResource(R.string.git_identity_save_global),
            onClick = { onSave(name, email, false) },
            enabled = valid,
            style = ChromeButtonStyle.PRIMARY,
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
        )
        ChromeButton(
            text = stringResource(R.string.git_identity_save_project),
            onClick = { onSave(name, email, true) },
            enabled = valid,
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
        )
        ChromeButton(
            text = stringResource(R.string.git_cancel),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
        )
    }
}

private const val COMMIT_MESSAGE_MAX_LINES = 3
