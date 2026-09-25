package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.extensions.install.SampleInfo
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitGroup
import dev.easyide.app.ui.kit.KitRow

/** The sample packs bundled with the app: tap one to review it on the usual capability sheet. They are optional and not enabled by default. */
@Composable
fun SamplePacksDialog(samples: List<SampleInfo>, onInstall: (String) -> Unit, onDismiss: () -> Unit) {
    KitDialog(
        title = stringResource(R.string.extui_sample_title),
        onDismiss = onDismiss,
        dismiss = KitAction(stringResource(R.string.extui_sample_close), onDismiss),
    ) {
        BasicText(stringResource(R.string.extui_sample_hint), style = Kit.text.caption.copy(color = Kit.colors.textMuted))
        if (samples.isEmpty()) {
            BasicText(stringResource(R.string.extui_sample_none), style = Kit.text.body.copy(color = Kit.colors.plainText))
        } else {
            KitGroup {
                samples.forEach { s -> KitRow(s.name, subtitle = s.description, onClick = { onInstall(s.id) }, id = "sample-${s.id}") }
            }
        }
    }
}
