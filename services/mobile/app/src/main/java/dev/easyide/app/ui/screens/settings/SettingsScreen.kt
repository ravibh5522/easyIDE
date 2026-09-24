package dev.easyide.app.ui.screens.settings

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.data.settings.SettingCategory
import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.SafeModeReason
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.TrustRequest
import dev.easyide.app.data.settings.TrustState
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.sandbox.external.ExternalFolderSync
import java.time.LocalDate

/**
 * Settings: schema-driven rows for built-in and contributed settings with
 * layer tabs and search, plus profiles, safe mode, project trust, JSON
 * editing, export/import, and (on the User tab) project storage and
 * environment management. Environments are listed here because deleting one
 * is a cross-project action - the usage count per row explains an "in use" refusal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    externalFolderSync: ExternalFolderSync,
    onOpenExtensions: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val system by viewModel.systemState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val errorDetail by viewModel.errorDetail.collectAsStateWithLifecycle()
    val importPreview by viewModel.importPreview.collectAsStateWithLifecycle()
    val jsonEditor by viewModel.jsonEditor.state.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var showProfiles by rememberSaveable { mutableStateOf(false) }
    var showResetAll by rememberSaveable { mutableStateOf(false) }
    var showTrust by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current
    val resources = LocalContext.current.resources

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BUNDLE_MIME)) { uri ->
        uri?.let(viewModel::onExport)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onImportPicked)
    }

    val messageText = message?.let { stringResource(messageRes(it)) } ?: errorDetail
    LaunchedEffect(messageText) {
        val text = messageText ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.onMessageShown()
    }

    val filter = SettingsFilter.parse(query)
    val sections = SettingsSearch.sections(
        settings = uiState.settings.schema.settings,
        filter = filter,
        snapshot = uiState.settings,
        layer = uiState.tab.layer,
        hidden = SettingsSchema.managedElsewhere,
    ) { s -> listOf(s.title.resolve(resources), s.description.resolve(resources)) }
    val layerName = stringResource(layerLabel(uiState.tab.layer))

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    SettingsOverflowMenu(
                        onEditJson = viewModel::openJson,
                        onEditKeybindings = viewModel::openKeybindingsJson,
                        onProfiles = { showProfiles = true },
                        onExport = { exportLauncher.launch(BUNDLE_NAME.format(LocalDate.now())) },
                        onImport = { importLauncher.launch(arrayOf(BUNDLE_MIME)) },
                        onResetAll = { showResetAll = true },
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            system.safeMode?.let { reason ->
                item(key = "safeMode") {
                    SafeModeBanner(reason) { viewModel.exitSafeMode { activity?.recreate() } }
                }
            }
            item(key = "layers") {
                SettingsLayerBar(uiState.tab, uiState.environments, uiState.projects, viewModel::onTabSelected, Modifier.contentWidth())
            }
            system.trust?.takeIf { it.state != TrustState.NOT_REQUIRED }?.let { request ->
                item(key = "trust") { TrustBanner(request, onReview = { showTrust = true }) }
            }
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.contentWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
            }
            sections.forEach { section ->
                item(key = "h:${section.id}") {
                    SectionHeader(section.category?.let { stringResource(it.title) } ?: stringResource(R.string.settings_contributed_section, section.title.orEmpty()))
                }
                if (section.category == SettingCategory.EXTENSIONS) {
                    item(key = MANAGE_EXTENSIONS_KEY) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.ext_manage_title)) },
                            supportingContent = { Text(stringResource(R.string.ext_manage_desc)) },
                            modifier = Modifier.contentWidth().clickable(onClick = onOpenExtensions),
                        )
                    }
                }
                items(section.rows, key = { it.key }) { setting ->
                    // The theme is chosen by looking, so on the user layer (its only
                    // writable one) it gets preview cards rather than a dropdown.
                    if (setting === SettingsSchema.themeMode && uiState.tab.layer == LayerId.USER) {
                        ThemePickerRow(uiState.settings, viewModel, Modifier.contentWidth())
                    } else {
                        SettingRow(
                            setting = setting,
                            snapshot = uiState.settings,
                            layer = uiState.tab.layer,
                            language = filter.language,
                            actions = viewModel,
                            onEditJson = viewModel::openJson,
                            modifier = Modifier.contentWidth(),
                        )
                    }
                }
                item(key = "d:${section.id}") { HorizontalDivider(modifier = Modifier.contentWidth().padding(vertical = Spacing.s)) }
            }
            // Storage and environments are device-wide, not layered settings; hidden while searching.
            if (!filter.isActive && uiState.tab is LayerTab.User) storageAndEnvironments(uiState, viewModel, externalFolderSync)
        }
    }

    jsonEditor?.let { state ->
        SettingsJsonEditor(state, viewModel.jsonEditor::onTextChanged, viewModel.jsonEditor::save, viewModel.jsonEditor::close)
    }
    importPreview?.let { ImportPreviewDialog(it, viewModel::onImportConfirmed, viewModel::onImportCancelled) }
    if (showResetAll) ResetAllDialog(layerName, viewModel::resetAll) { showResetAll = false }
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
    val trust = system.trust
    if (showTrust && trust != null) {
        TrustReview(trust, viewModel) { showTrust = false }
    }
}

/** The sheet itself only asks while undecided; a stored decision is first forgotten, which re-asks. */
@Composable
private fun TrustReview(request: TrustRequest, viewModel: SettingsViewModel, onDone: () -> Unit) {
    if (request.state != TrustState.PENDING) {
        LaunchedEffect(request.fingerprint) { viewModel.reviewProjectTrust(request.projectId) }
        return
    }
    ProjectTrustDialog(
        request = request,
        onAllow = { viewModel.allowProjectExec(request); onDone() },
        onNotNow = { viewModel.deferProjectExec(request); onDone() },
        onNever = { viewModel.denyProjectExec(request); onDone() },
    )
}

@Composable
private fun SettingsOverflowMenu(
    onEditJson: () -> Unit,
    onEditKeybindings: () -> Unit,
    onProfiles: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onResetAll: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.settings_more_actions))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        listOf(
            R.string.settings_edit_json to onEditJson,
            R.string.settings_edit_keybindings to onEditKeybindings,
            R.string.settings_profiles_title to onProfiles,
            R.string.settings_export to onExport,
            R.string.settings_import to onImport,
            R.string.settings_reset_all_title to onResetAll,
        ).forEach { (label, action) ->
            DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { open = false; action() })
        }
    }
}

@Composable
private fun SafeModeBanner(reason: SafeModeReason, onExit: () -> Unit) {
    Card(modifier = Modifier.contentWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        Row(modifier = Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.safe_mode_banner_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(safeModeReasonRes(reason)), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onExit) { Text(stringResource(R.string.safe_mode_exit)) }
        }
    }
}

@Composable
private fun TrustBanner(request: TrustRequest, onReview: () -> Unit) {
    Card(modifier = Modifier.contentWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        Row(modifier = Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(trustStateRes(request.state)), modifier = Modifier.weight(1f))
            TextButton(onClick = onReview) { Text(stringResource(R.string.trust_review)) }
        }
    }
}

private fun LazyListScope.storageAndEnvironments(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    externalFolderSync: ExternalFolderSync,
) {
    item(key = "storage") { SectionHeader(stringResource(R.string.settings_storage_section)) }
    item(key = "storageRow") {
        StorageFolderRow(
            folderName = uiState.defaultProjectsFolderName,
            externalFolderSync = externalFolderSync,
            onChosen = { viewModel.onDefaultProjectsFolderChosen(it) },
            onPickFailed = viewModel::onDefaultProjectsFolderPickFailed,
        )
    }
    item(key = "storageDivider") { HorizontalDivider(modifier = Modifier.contentWidth().padding(vertical = Spacing.s)) }
    item(key = "sandbox") { SectionHeader(stringResource(R.string.settings_sandbox_section)) }
    if (uiState.environments.isEmpty()) {
        item(key = "noEnvironments") {
            Text(
                text = stringResource(R.string.settings_environments_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.contentWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
            )
        }
    }
    items(uiState.environments, key = { it.environment.id }) { item ->
        EnvironmentRow(
            item = item,
            isDefault = uiState.defaultEnvironmentId == item.environment.id,
            onSetDefault = { viewModel.onDefaultEnvironmentSelected(item.environment.id) },
            onDelete = { viewModel.onDeleteEnvironment(item.environment.id) },
        )
    }
}

private fun messageRes(m: SettingsMessage): Int = when (m) {
    SettingsMessage.WRITE_FAILED -> R.string.settings_msg_write_failed
    SettingsMessage.EXPORTED -> R.string.settings_msg_exported
    SettingsMessage.EXPORT_FAILED -> R.string.settings_msg_export_failed
    SettingsMessage.IMPORTED -> R.string.settings_msg_imported
    SettingsMessage.IMPORT_FAILED -> R.string.settings_msg_import_failed
    SettingsMessage.PROFILE_FAILED -> R.string.settings_msg_profile_failed
    SettingsMessage.FOLDER_PICK_FAILED -> R.string.settings_msg_folder_failed
    SettingsMessage.ENVIRONMENT_DELETE_FAILED -> R.string.settings_msg_environment_failed
    SettingsMessage.SAFE_MODE_EXIT_FAILED -> R.string.settings_msg_safe_mode_failed
}

private fun safeModeReasonRes(r: SafeModeReason): Int = when (r) {
    SafeModeReason.SETTING -> R.string.safe_mode_reason_setting
    SafeModeReason.LAUNCHER_SHORTCUT -> R.string.safe_mode_reason_shortcut
    SafeModeReason.AUTO_CRASH -> R.string.safe_mode_reason_crash
}

private fun trustStateRes(s: TrustState): Int = when (s) {
    TrustState.TRUSTED -> R.string.trust_state_trusted
    TrustState.DENIED -> R.string.trust_state_denied
    TrustState.DEFERRED -> R.string.trust_state_deferred
    TrustState.PENDING, TrustState.NOT_REQUIRED -> R.string.trust_state_pending
}

private const val MANAGE_EXTENSIONS_KEY = "extensions.manage"
private const val BUNDLE_MIME = "application/zip"
private const val BUNDLE_NAME = "easyide-settings-%s.zip"
