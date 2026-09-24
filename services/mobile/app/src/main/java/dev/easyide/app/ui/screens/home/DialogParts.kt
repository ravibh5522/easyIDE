package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import dev.easyide.app.R
import dev.easyide.app.ui.components.label
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitChoice
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.Tone
import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.SandboxEnvironment

/** Names and repository addresses: no capital, no autocorrect (U-INT-06). */
internal val NAME_KEYBOARD = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done)
internal val URL_KEYBOARD = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)

/** The dialog's content column: one question, fields and notes a step apart. */
@Composable
internal fun DialogBody(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Kit.space.m), content = content)
}

/** Why the last attempt failed, kept inside the open dialog so the input can be corrected. */
@Composable
internal fun FailureBanner(failure: HomeFailure?) {
    if (failure != null) KitBanner(failure.text(), tone = Tone.Danger)
}

/**
 * One environment per line. A line names its backend, and its state unless it is ready, so an
 * environment that is still installing or failed is not chosen by mistake.
 */
@Composable
internal fun EnvironmentChoice(
    environments: List<SandboxEnvironment>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val selected = environments.find { it.id == selectedId } ?: return
    val labels = environments.associate { env ->
        val state = env.state.takeIf { it != EnvironmentState.READY }?.tagText()
        env.id to joinParts(env.label, env.backend.label(), state)
    }
    KitChoice(environments, selected, { labels.getValue(it.id) }, onSelect = { if (enabled) onSelect(it.id) })
}

/** Cloning and importing need a Linux environment; without one the only next step is installing it. */
@Composable
internal fun InstallLinuxDialog(onInstall: () -> Unit, onDismiss: () -> Unit) {
    KitDialog(
        title = stringResource(R.string.home_install_prompt_title),
        onDismiss = onDismiss,
        confirm = KitAction(stringResource(R.string.home_install_prompt_confirm), onInstall),
        dismiss = KitAction(stringResource(R.string.action_cancel), onDismiss),
    ) {
        BodyText(stringResource(R.string.home_install_prompt_body))
    }
}

/** Ending a workspace session drops its unsaved buffers, so it names what goes and what stays. */
@Composable
internal fun StopSessionDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    KitDialog(
        title = stringResource(R.string.home_stop_session_title, name),
        onDismiss = onDismiss,
        tone = Tone.Danger,
        confirm = KitAction(stringResource(R.string.home_stop_session_confirm), onConfirm),
        dismiss = KitAction(stringResource(R.string.action_cancel), onDismiss),
    ) {
        BodyText(stringResource(R.string.home_stop_session_body))
    }
}
