package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.data.settings.TrustRequest
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitDialog

/**
 * Consent for a project's exec-bearing settings (threat-model M-12, LLD 12): lists every affected
 * server or key with its exact argv, env and options, because "this project wants to run something"
 * is not an informed choice. Allow stores the fingerprint; Not now blocks for this session; Never
 * stores a denial for exactly this content, so a changed file asks again.
 */
@Composable
fun ProjectTrustDialog(request: TrustRequest, onAllow: () -> Unit, onNotNow: () -> Unit, onNever: () -> Unit) {
    KitDialog(
        title = stringResource(R.string.trust_title),
        onDismiss = onNotNow,
        confirm = KitAction(stringResource(R.string.trust_allow), onAllow),
        dismiss = KitAction(stringResource(R.string.trust_not_now), onNotNow),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Kit.space.s)) {
            BodyText(stringResource(R.string.trust_body))
            request.items.forEach { item ->
                val title = if (item.isServer) stringResource(R.string.trust_server, item.key) else item.key
                Column {
                    BodyText(item.language?.let { stringResource(R.string.trust_in_language, title, it) } ?: title)
                    item.fields.forEach { (name, json) -> BodyText(stringResource(R.string.trust_field, name, json), mono = true) }
                }
            }
            KitButton(stringResource(R.string.trust_never), onNever, style = KitButtonStyle.Danger)
        }
    }
}
