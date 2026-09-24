package dev.easyide.app.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.State
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
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
    AgeUnit.MINUTES -> pluralStringResource(R.plurals.home_age_minutes, amount, amount)
    AgeUnit.HOURS -> pluralStringResource(R.plurals.home_age_hours, amount, amount)
    AgeUnit.DAYS -> pluralStringResource(R.plurals.home_age_days, amount, amount)
    AgeUnit.WEEKS -> pluralStringResource(R.plurals.home_age_weeks, amount, amount)
    AgeUnit.MONTHS -> pluralStringResource(R.plurals.home_age_months, amount, amount)
    AgeUnit.YEARS -> pluralStringResource(R.plurals.home_age_years, amount, amount)
}

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
