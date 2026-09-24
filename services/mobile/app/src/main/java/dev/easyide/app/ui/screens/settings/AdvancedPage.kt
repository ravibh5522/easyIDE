package dev.easyide.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import java.time.LocalDate

/**
 * Advanced: profiles, the settings files, export and import, and reset. These act on files and on
 * the selected layer rather than on one schema key. The reset and the profile changes ask first.
 */
@Composable
internal fun AdvancedPage(viewModel: SettingsViewModel, ui: SettingsUiState) {
    val system by viewModel.systemState.collectAsStateWithLifecycle()
    var showProfiles by rememberSaveable { mutableStateOf(false) }
    var showReset by rememberSaveable { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BUNDLE_MIME)) { uri -> uri?.let(viewModel::onExport) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::onImportPicked) }

    KitSection(stringResource(R.string.settings_profiles_title)) {
        KitRow(
            title = stringResource(R.string.setting_profile_title),
            subtitle = stringResource(R.string.setting_profile_desc),
            trailing = { ValueText(system.activeProfile) },
            onClick = { showProfiles = true },
            id = "settings-action:profiles",
        )
    }
    KitSection(stringResource(R.string.settings_files_section)) {
        KitRow(stringResource(R.string.settings_edit_json), onClick = viewModel::openJson, id = "settings-action:json")
        KitRow(stringResource(R.string.settings_edit_keybindings), onClick = viewModel::openKeybindingsJson, id = "settings-action:keybindings-json")
        KitRow(stringResource(R.string.settings_export), onClick = { exportLauncher.launch(BUNDLE_NAME.format(LocalDate.now())) }, id = "settings-action:export")
        KitRow(stringResource(R.string.settings_import), onClick = { importLauncher.launch(arrayOf(BUNDLE_MIME)) }, id = "settings-action:import")
    }
    KitSection(stringResource(R.string.settings_reset_section)) {
        KitRow(
            title = stringResource(R.string.settings_reset_all_title),
            subtitle = stringResource(R.string.settings_reset_all_desc),
            onClick = { showReset = true },
            id = "settings-action:reset-all",
        )
    }

    if (showProfiles) {
        ProfilesDialog(
            profiles = system.profiles,
            active = system.activeProfile,
            onSwitch = viewModel::switchProfile,
            onCreate = viewModel::createProfile,
            onRename = viewModel::renameProfile,
            onDelete = viewModel::deleteProfile,
            onDismiss = { showProfiles = false },
        )
    }
    if (showReset) ResetAllDialog(stringResource(layerLabel(ui.tab.layer)), viewModel::resetAll) { showReset = false }
}

private const val BUNDLE_MIME = "application/zip"
private const val BUNDLE_NAME = "easyide-settings-%s.zip"
