package dev.easyide.app.ui.screens.onboarding

import androidx.annotation.StringRes
import dev.easyide.app.R

@get:StringRes
internal val InstallFailure.title: Int
    get() = when (this) {
        InstallFailure.NETWORK -> R.string.setup_fail_network_title
        InstallFailure.SERVER -> R.string.setup_fail_server_title
        InstallFailure.CORRUPT_DOWNLOAD -> R.string.setup_fail_corrupt_title
        InstallFailure.STORAGE_FULL -> R.string.setup_fail_storage_full_title
        InstallFailure.STORAGE -> R.string.setup_fail_storage_title
        InstallFailure.UNSUPPORTED_DEVICE -> R.string.setup_fail_unsupported_title
        InstallFailure.SETUP_STEP -> R.string.setup_fail_setup_title
        InstallFailure.UNKNOWN -> R.string.setup_fail_unknown_title
    }

@get:StringRes
internal val InstallFailure.advice: Int
    get() = when (this) {
        InstallFailure.NETWORK -> R.string.setup_fail_network_advice
        InstallFailure.SERVER -> R.string.setup_fail_server_advice
        InstallFailure.CORRUPT_DOWNLOAD -> R.string.setup_fail_corrupt_advice
        InstallFailure.STORAGE_FULL -> R.string.setup_fail_storage_full_advice
        InstallFailure.STORAGE -> R.string.setup_fail_storage_advice
        InstallFailure.UNSUPPORTED_DEVICE -> R.string.setup_fail_unsupported_advice
        InstallFailure.SETUP_STEP -> R.string.setup_fail_setup_advice
        InstallFailure.UNKNOWN -> R.string.setup_fail_unknown_advice
    }

/** A dropped connection resumes where it stopped, which is worth saying on the button. */
@get:StringRes
internal val InstallFailure.retryLabel: Int
    get() = if (this == InstallFailure.NETWORK) R.string.setup_resume else R.string.setup_retry
