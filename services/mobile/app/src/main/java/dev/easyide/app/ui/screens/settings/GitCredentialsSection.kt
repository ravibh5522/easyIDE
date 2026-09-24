package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.screens.workspace.git.GitCredentialDialog
import dev.easyide.sandbox.git.GitCredentialEntry

/**
 * Settings > Git > Credentials: the hosts that have a token, and add/forget. A row shows host and
 * username only - a stored token cannot be viewed, only replaced (by adding the same host again) or
 * forgotten.
 */
@Composable
fun GitCredentialsSection(
    entries: List<GitCredentialEntry>,
    onSave: suspend (host: String, username: String, token: String) -> Boolean,
    onForget: (String) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<String?>(null) }

    KitSection(stringResource(R.string.git_credentials_title)) {
        if (entries.isEmpty()) KitEmptyState(EmptyArt.Prompt, stringResource(R.string.git_credentials_empty))
        entries.forEach { entry ->
            KitRow(
                title = entry.host,
                subtitle = stringResource(R.string.git_credentials_row_detail, entry.username),
                mono = true,
                trailing = {
                    KitIconButton(Icons.Filled.Delete, stringResource(R.string.git_credentials_forget_cd, entry.host), { forgetting = entry.host })
                },
            )
        }
    }
    Column(Modifier.padding(horizontal = Kit.space.l, vertical = Kit.space.s)) {
        KitButton(stringResource(R.string.git_credentials_add), { adding = true }, style = KitButtonStyle.Secondary)
    }

    if (adding) GitCredentialDialog(initialHost = "", onSave = onSave, onDismiss = { adding = false })
    forgetting?.let { host ->
        KitDialog(
            title = stringResource(R.string.git_credentials_forget_title, host),
            onDismiss = { forgetting = null },
            confirm = KitAction(stringResource(R.string.git_credentials_forget)) { onForget(host); forgetting = null },
            dismiss = KitAction(stringResource(R.string.git_cancel)) { forgetting = null },
            tone = Tone.Danger,
        ) {
            BodyText(stringResource(R.string.git_credentials_forget_body))
        }
    }
}
