package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.easyide.app.R
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.sandbox.git.GitUrl
import kotlinx.coroutines.launch

/**
 * Collects a personal access token for a git host. Shared by Settings and the
 * source-control pane (where an authentication failure offers it in place).
 *
 * The token is typed into a password field and handed to [onSave] once; nothing
 * here or anywhere else in the UI can display it again. [onSave] returns false
 * when the values cannot be stored, which keeps the dialog open with a message.
 *
 * @param initialHost prefilled and left editable, so a token can be added for a
 *   self-hosted forge as easily as for github.com.
 */
@Composable
fun GitCredentialDialog(
    initialHost: String,
    onSave: suspend (host: String, username: String, token: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(initialHost) }
    var username by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val normalisedHost = GitUrl.host(host)
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.git_credential_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.git_credential_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; failed = false },
                    label = { Text(stringResource(R.string.git_credential_host)) },
                    singleLine = true,
                    isError = host.isNotBlank() && normalisedHost == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; failed = false },
                    label = { Text(stringResource(R.string.git_credential_username)) },
                    supportingText = { Text(stringResource(R.string.git_credential_username_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; failed = false },
                    label = { Text(stringResource(R.string.git_credential_token)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = failed,
                    supportingText = if (failed) {
                        { Text(stringResource(R.string.git_credential_save_failed)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = normalisedHost != null && token.isNotBlank(),
                onClick = {
                    scope.launch {
                        val saved = onSave(requireNotNull(normalisedHost), username.trim(), token.trim())
                        if (saved) onDismiss() else failed = true
                    }
                },
            ) { Text(stringResource(R.string.git_credential_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.git_cancel)) } },
    )
}
