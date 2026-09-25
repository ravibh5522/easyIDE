package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.screens.workspace.DialogText
import dev.easyide.app.ui.screens.workspace.NAME_KEYBOARD
import dev.easyide.app.ui.screens.workspace.URL_KEYBOARD
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
    val gap = Modifier.padding(top = Kit.space.s)

    KitDialog(
        title = stringResource(R.string.git_credential_dialog_title),
        onDismiss = onDismiss,
        confirm = if (normalisedHost != null && token.isNotBlank()) {
            KitAction(stringResource(R.string.git_credential_save)) {
                scope.launch {
                    val saved = onSave(normalisedHost, username.trim(), token.trim())
                    if (saved) onDismiss() else failed = true
                }
            }
        } else null,
        dismiss = KitAction(stringResource(R.string.git_cancel), onDismiss),
    ) {
        DialogText(stringResource(R.string.git_credential_dialog_body), muted = true)
        KitField(
            value = host,
            onValueChange = { host = it; failed = false },
            modifier = gap,
            label = stringResource(R.string.git_credential_host),
            error = if (host.isNotBlank() && normalisedHost == null) stringResource(R.string.wp_git_invalid_host) else null,
            mono = true,
            keyboard = URL_KEYBOARD,
        )
        KitField(
            value = username,
            onValueChange = { username = it; failed = false },
            modifier = gap,
            label = stringResource(R.string.git_credential_username),
            hint = stringResource(R.string.git_credential_username_hint),
            mono = true,
            keyboard = NAME_KEYBOARD,
        )
        KitField(
            value = token,
            onValueChange = { token = it; failed = false },
            modifier = gap,
            label = stringResource(R.string.git_credential_token),
            error = if (failed) stringResource(R.string.git_credential_save_failed) else null,
            mono = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        )
    }
}
