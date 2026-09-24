package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.screens.workspace.git.GitCredentialDialog
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.sandbox.git.GitCredentialEntry

/**
 * Settings > Git > Credentials: the hosts that have a token, and add/forget.
 * A row shows host and username only - a stored token cannot be viewed, only
 * replaced (by adding the same host again) or forgotten.
 */
@Composable
fun GitCredentialsSection(
    entries: List<GitCredentialEntry>,
    onSave: suspend (host: String, username: String, token: String) -> Boolean,
    onForget: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.git_credentials_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        )
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.git_credentials_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
            )
        }
        entries.forEach { entry ->
            ListItem(
                leadingContent = { Icon(Icons.Filled.Key, contentDescription = null) },
                headlineContent = { Text(entry.host) },
                supportingContent = {
                    Text(stringResource(R.string.git_credentials_row_detail, entry.username))
                },
                trailingContent = {
                    IconButton(onClick = { forgetting = entry.host }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.git_credentials_forget_cd, entry.host),
                        )
                    }
                },
            )
        }
        TextButton(
            onClick = { adding = true },
            modifier = Modifier.padding(horizontal = Spacing.s),
        ) { Text(stringResource(R.string.git_credentials_add)) }
    }

    if (adding) {
        GitCredentialDialog(initialHost = "", onSave = onSave, onDismiss = { adding = false })
    }
    forgetting?.let { host ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(stringResource(R.string.git_credentials_forget_title, host)) },
            text = { Text(stringResource(R.string.git_credentials_forget_body)) },
            confirmButton = {
                TextButton(onClick = { onForget(host); forgetting = null }) {
                    Text(stringResource(R.string.git_credentials_forget))
                }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text(stringResource(R.string.git_cancel)) } },
        )
    }
}
