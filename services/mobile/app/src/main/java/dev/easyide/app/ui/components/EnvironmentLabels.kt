package dev.easyide.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Tone
import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.SandboxBackend

/** User-facing names for sandbox enums, so a raw `PROOT` or `NOT_PROVISIONED` never reaches the screen. */
@Composable
fun SandboxBackend.label(): String = stringResource(
    when (this) {
        SandboxBackend.PROOT -> R.string.backend_proot
        SandboxBackend.CHROOT -> R.string.backend_chroot
    },
)

@Composable
fun EnvironmentState.label(): String = stringResource(
    when (this) {
        EnvironmentState.READY -> R.string.environment_state_ready
        EnvironmentState.PROVISIONING -> R.string.environment_state_provisioning
        EnvironmentState.FAILED -> R.string.environment_state_failed
        EnvironmentState.NOT_PROVISIONED -> R.string.environment_state_not_installed
    },
)

/** The tag tone of an environment's state: the word carries the meaning, the tone only agrees with it. */
fun EnvironmentState.tone(): Tone = when (this) {
    EnvironmentState.READY -> Tone.Success
    EnvironmentState.PROVISIONING -> Tone.Info
    EnvironmentState.FAILED -> Tone.Danger
    EnvironmentState.NOT_PROVISIONED -> Tone.Neutral
}
