package dev.easyide.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
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
