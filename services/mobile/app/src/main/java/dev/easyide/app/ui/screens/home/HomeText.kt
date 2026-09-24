package dev.easyide.app.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.State
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Tone
import dev.easyide.sandbox.model.EnvironmentState
import kotlinx.coroutines.delay

/** The current time, refreshed on a slow tick so relative labels do not go stale while Home stays open. */
@Composable
internal fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(HomeMetrics.CLOCK_TICK_MS)
        value = System.currentTimeMillis()
    }
}

@Composable
internal fun RelativeAge.text(): String = when (unit) {
    AgeUnit.NOW -> stringResource(R.string.home_age_now)
    AgeUnit.MINUTES -> stringResource(R.string.home_age_minutes, amount)
    AgeUnit.HOURS -> stringResource(R.string.home_age_hours, amount)
    AgeUnit.DAYS -> stringResource(R.string.home_age_days, amount)
    AgeUnit.WEEKS -> stringResource(R.string.home_age_weeks, amount)
    AgeUnit.MONTHS -> stringResource(R.string.home_age_months, amount)
    AgeUnit.YEARS -> stringResource(R.string.home_age_years, amount)
}

@Composable
internal fun ProcessSize.text(): String = when (unit) {
    SizeUnit.MB -> stringResource(R.string.home_running_size_mb, value)
    SizeUnit.GB -> stringResource(R.string.home_running_size_gb, value)
}

@Composable
internal fun RunningPhase.label(): String = stringResource(
    when (this) {
        RunningPhase.RUNNING -> R.string.home_running_running
        RunningPhase.STARTING -> R.string.home_running_starting
        RunningPhase.FAILED -> R.string.home_running_failed
    },
)

internal fun RunningPhase.tone(): Tone = when (this) {
    RunningPhase.RUNNING -> Tone.Success
    RunningPhase.STARTING -> Tone.Info
    RunningPhase.FAILED -> Tone.Danger
}

@Composable
internal fun EnvironmentState.tagText(): String = stringResource(
    when (this) {
        EnvironmentState.READY -> R.string.home_env_ok
        EnvironmentState.PROVISIONING -> R.string.home_env_installing
        EnvironmentState.FAILED -> R.string.home_env_failed
        EnvironmentState.NOT_PROVISIONED -> R.string.home_env_missing_rootfs
    },
)

internal fun EnvironmentState.tone(): Tone = when (this) {
    EnvironmentState.READY -> Tone.Success
    EnvironmentState.PROVISIONING -> Tone.Info
    EnvironmentState.FAILED -> Tone.Danger
    EnvironmentState.NOT_PROVISIONED -> Tone.Neutral
}

/** Where a project's files live, for a row or the page header. */
@Composable
internal fun ProjectListItem.locationLabel(): String =
    meta?.externalFolderName?.let { stringResource(R.string.home_location_folder, it) }
        ?: if (project.externalFolderUri != null) {
            stringResource(R.string.home_location_linked_folder)
        } else {
            stringResource(R.string.home_location_app_storage)
        }

/** Joins the parts of a support line with the middle-dot separator; empty parts are skipped. */
@Composable
internal fun joinParts(vararg parts: String?): String =
    parts.filter { !it.isNullOrEmpty() }.joinToString(stringResource(R.string.home_separator))

@Composable
internal fun HomeFailure.text(): String = when (this) {
    HomeFailure.NameTaken -> stringResource(R.string.home_error_name_taken)
    HomeFailure.NameBlank -> stringResource(R.string.home_error_name_blank)
    HomeFailure.NoReadyEnvironment -> stringResource(R.string.home_error_no_ready_environment)
    is HomeFailure.CloneAuthentication -> stringResource(R.string.home_error_clone_auth, host)
    HomeFailure.CloneNotFound -> stringResource(R.string.home_error_clone_not_found)
    is HomeFailure.CloneFailed -> stringResource(R.string.home_error_clone_failed, detail)
    is HomeFailure.Other -> detail?.let { stringResource(R.string.home_error_other_detail, it) }
        ?: stringResource(R.string.home_error_other)
}

@Composable
internal fun HomeMessage.text(): String = when (this) {
    is HomeMessage.Renamed -> stringResource(R.string.home_message_renamed, name)
    is HomeMessage.Duplicated -> stringResource(R.string.home_message_duplicated, name)
    is HomeMessage.Deleted -> stringResource(R.string.home_message_deleted, name)
    is HomeMessage.EnvironmentChanged -> stringResource(R.string.home_message_environment_changed, name, environment)
    is HomeMessage.Imported -> stringResource(R.string.home_message_imported, name)
    is HomeMessage.Cloned -> stringResource(R.string.home_message_cloned, name)
    HomeMessage.FolderPickFailed -> stringResource(R.string.home_message_folder_pick_failed)
}
