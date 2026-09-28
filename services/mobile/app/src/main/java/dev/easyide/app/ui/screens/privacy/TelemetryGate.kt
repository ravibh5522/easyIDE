package dev.easyide.app.ui.screens.privacy

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitDialog

/**
 * The mandatory telemetry agreement: shown on first launch (new installs, before setup) and on the
 * first launch after an upgrade (existing installs). Back does nothing; the only ways out are
 * agreeing or leaving the app. Withdrawal later is the Privacy setting.
 */
@Composable
fun TelemetryGate(onAgree: () -> Unit, onExit: () -> Unit) {
    KitDialog(
        title = stringResource(R.string.telemetry_gate_title),
        onDismiss = {},
        confirm = KitAction(stringResource(R.string.telemetry_gate_agree), onAgree),
        dismiss = KitAction(stringResource(R.string.telemetry_gate_exit), onExit),
    ) {
        BasicText(
            stringResource(R.string.telemetry_gate_body),
            Modifier.padding(top = Kit.space.xs),
            style = Kit.text.body.copy(color = Kit.colors.plainText),
        )
    }
}
