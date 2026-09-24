package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.data.settings.TrustRequest

/**
 * Consent for a project's exec-bearing settings (threat-model M-12, LLD 12):
 * lists every affected server or key with its exact argv, env and options,
 * because "this project wants to run something" is not an informed choice.
 * Allow stores the fingerprint; Not now blocks for this session; Never stores
 * a denial for exactly this content, so a changed file asks again.
 */
@Composable
fun ProjectTrustDialog(request: TrustRequest, onAllow: () -> Unit, onNotNow: () -> Unit, onNever: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text(stringResource(R.string.trust_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(stringResource(R.string.trust_body))
                LazyColumn(modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT_DP.dp), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    items(request.items) { item ->
                        Column {
                            val title = if (item.isServer) stringResource(R.string.trust_server, item.key) else item.key
                            Text(
                                item.language?.let { stringResource(R.string.trust_in_language, title, it) } ?: title,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            item.fields.forEach { (name, json) ->
                                Text(
                                    stringResource(R.string.trust_field, name, json),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.trust_allow)) } },
        dismissButton = {
            Column {
                TextButton(onClick = onNotNow) { Text(stringResource(R.string.trust_not_now)) }
                TextButton(onClick = onNever) { Text(stringResource(R.string.trust_never)) }
            }
        },
    )
}

private const val LIST_MAX_HEIGHT_DP = 320
