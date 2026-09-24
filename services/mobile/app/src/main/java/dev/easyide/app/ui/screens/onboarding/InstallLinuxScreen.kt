package dev.easyide.app.ui.screens.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.ui.components.FlowFrame
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton

/**
 * Install Linux outside first-run: what Home's prompt opens for a user who skipped the environment
 * step. The same content as onboarding, in a [FlowFrame] with its own Back; leaving while installing
 * cancels it (the download resumes later). Done, the one primary action, appears once Linux is in place.
 */
@Composable
fun InstallLinuxScreen(
    setup: EnvironmentSetupViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by setup.state.collectAsStateWithLifecycle()
    val leave = {
        setup.cancel()
        onBack()
    }
    BackHandler(onBack = leave)

    FlowFrame(
        title = stringResource(R.string.setup_screen_title),
        onBack = leave,
        modifier = modifier,
        footer = if (state.stage is SetupStage.Ready) {
            { KitButton(stringResource(R.string.setup_done), onBack, Modifier.fillMaxWidth()) }
        } else null,
    ) {
        EnvironmentSetupContent(
            state = state,
            onImageSelected = setup::onImageSelected,
            onInstall = setup::install,
            onCancel = setup::cancel,
            onChooseAnother = setup::backToChoosing,
            modifier = Modifier.padding(vertical = Kit.space.m),
        )
    }
}
