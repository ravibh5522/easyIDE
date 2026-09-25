package dev.easyide.app.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.diagnostics.ChecksumStatus
import dev.easyide.app.diagnostics.ProotFailure
import dev.easyide.app.diagnostics.ProotStatus
import dev.easyide.app.ui.kit.Tone
import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.SandboxBackend

internal val ChecksumStatus.canVerify: Boolean
    get() = this != ChecksumStatus.ARCHIVE_REMOVED && this != ChecksumStatus.NO_PINNED_ROOTFS

@StringRes
internal fun backendText(backend: SandboxBackend): Int = when (backend) {
    SandboxBackend.PROOT -> R.string.diag_backend_proot
    SandboxBackend.CHROOT -> R.string.diag_backend_chroot
}

@StringRes
internal fun stateText(state: EnvironmentState): Int = when (state) {
    EnvironmentState.NOT_PROVISIONED -> R.string.diag_state_not_provisioned
    EnvironmentState.PROVISIONING -> R.string.diag_state_provisioning
    EnvironmentState.READY -> R.string.diag_state_ready
    EnvironmentState.FAILED -> R.string.diag_state_failed
}

@StringRes
internal fun checksumText(status: ChecksumStatus): Int = when (status) {
    ChecksumStatus.NOT_CHECKED -> R.string.diag_checksum_not_checked
    ChecksumStatus.VERIFIED -> R.string.diag_checksum_verified
    ChecksumStatus.MISMATCH -> R.string.diag_checksum_mismatch
    ChecksumStatus.ARCHIVE_REMOVED -> R.string.diag_checksum_archive_removed
    ChecksumStatus.NO_PINNED_ROOTFS -> R.string.diag_checksum_no_pinned
}

/** Only a real result is toned: "not checked" and "archive removed" are neither good nor bad. */
internal fun checksumTone(status: ChecksumStatus): Tone? = when (status) {
    ChecksumStatus.VERIFIED -> Tone.Success
    ChecksumStatus.MISMATCH -> Tone.Danger
    else -> null
}

@StringRes
internal fun checksumTag(status: ChecksumStatus): Int? = when (status) {
    ChecksumStatus.VERIFIED -> R.string.diag_tag_verified
    ChecksumStatus.MISMATCH -> R.string.diag_tag_mismatch
    else -> null
}

@Composable
internal fun prootFailureText(status: ProotStatus.Unavailable): String {
    val detail = status.detail.orEmpty()
    return when (status.failure) {
        ProotFailure.MISSING -> stringResource(R.string.diag_proot_missing)
        ProotFailure.TIMED_OUT -> stringResource(R.string.diag_proot_timed_out)
        ProotFailure.EXIT_CODE -> stringResource(R.string.diag_proot_exit_code, detail)
        ProotFailure.NO_OUTPUT -> stringResource(R.string.diag_proot_no_output)
        ProotFailure.START_FAILED -> stringResource(R.string.diag_proot_start_failed, detail)
    }
}
