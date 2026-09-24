package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.TrustRequest
import dev.easyide.app.data.settings.TrustState
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone

/**
 * The top of a layered page: which layer the edits go to and the "Edit as JSON" action, the
 * language override where the page has per-language rows, and the trust banner while the selected
 * project layer asks for permission to run commands.
 */
@Composable
internal fun PageHeader(viewModel: SettingsViewModel, ui: SettingsUiState, ctx: SettingsContext, page: SettingsCategory?, env: PageEnv) {
    if (page?.layered == false) return
    val system by viewModel.systemState.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    var reviewing by rememberSaveable { mutableStateOf(false) }
    val perLanguage = page == null || pageSettings(page, env.settings).any { it.scope.languageOverridable }
    val gutter = Modifier.padding(start = Kit.space.l, end = Kit.space.l, top = Kit.space.l)

    Row(gutter, Arrangement.SpaceBetween, Alignment.CenterVertically) {
        KitTag(layerText(ui), tone = Tone.Accent)
        KitButton(stringResource(R.string.settings_edit_json), ctx.onEditJson, style = KitButtonStyle.Ghost)
    }
    system.trust?.takeIf { it.state != TrustState.NOT_REQUIRED && ui.tab is LayerTab.Project }?.let { request ->
        KitBanner(
            stringResource(trustStateRes(request.state)), gutter, Tone.Warning,
            KitAction(stringResource(R.string.trust_review)) { reviewing = true },
        )
        if (reviewing) TrustReview(request, viewModel) { reviewing = false }
    }
    if (perLanguage) {
        KitField(
            value = language,
            onValueChange = viewModel::onLanguageChanged,
            label = stringResource(R.string.settings_language_override),
            hint = stringResource(R.string.settings_language_override_hint),
            mono = true,
            modifier = gutter,
        )
    }
}

@Composable
private fun layerText(ui: SettingsUiState): String {
    val layer = stringResource(layerLabel(ui.tab.layer))
    val name = when (val tab = ui.tab) {
        LayerTab.User -> null
        is LayerTab.Environment -> ui.environments.firstOrNull { it.environment.id == tab.envId }?.environment?.label
        is LayerTab.Project -> ui.projects.firstOrNull { it.id == tab.projectId }?.name
    }
    return if (name == null) layer else stringResource(R.string.settings_layer_named, layer, name)
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

private fun trustStateRes(s: TrustState): Int = when (s) {
    TrustState.TRUSTED -> R.string.trust_state_trusted
    TrustState.DENIED -> R.string.trust_state_denied
    TrustState.DEFERRED -> R.string.trust_state_deferred
    TrustState.PENDING, TrustState.NOT_REQUIRED -> R.string.trust_state_pending
}

/** Overlays that belong to no single control: the JSON editor and the import preview. */
@Composable
internal fun PageDialogs(viewModel: SettingsViewModel) {
    val jsonEditor by viewModel.jsonEditor.state.collectAsStateWithLifecycle()
    val importPreview by viewModel.importPreview.collectAsStateWithLifecycle()
    jsonEditor?.let { state ->
        SettingsJsonEditor(state, viewModel.jsonEditor::onTextChanged, viewModel.jsonEditor::save, viewModel.jsonEditor::close, viewModel.jsonEditor::suggest)
    }
    importPreview?.let { ImportPreviewDialog(it, viewModel::onImportConfirmed, viewModel::onImportCancelled) }
}

/** Matches across every page: each row carries its page name so a result is not context-free. */
@Composable
internal fun SearchResultsPage(env: PageEnv, filter: SettingsFilter, ui: SettingsUiState) {
    val textOf = rememberSettingText()
    if (!filter.isActive) {
        KitEmptyState(EmptyArt.Search, stringResource(R.string.settings_search_prompt))
        return
    }
    val results = SettingsSearch.ranked(
        SettingsSearch.matching(env.settings, filter, ui.settings, ui.tab.layer, SettingsSchema.managedElsewhere, textOf),
    )
    if (results.isEmpty()) {
        KitEmptyState(EmptyArt.Search, stringResource(R.string.settings_search_empty))
        return
    }
    KitSection(null) {
        results.forEach { SettingRowFor(it, env, contextLabel = stringResource(SettingsCategory.of(it).title)) }
    }
}

/** A page whose content is the schema rows of one category plus, for some, a link or a section of its own. */
@Composable
internal fun CategoryPage(viewModel: SettingsViewModel, page: SettingsCategory, ui: SettingsUiState, env: PageEnv, host: SettingsHost) {
    val user = ui.tab.layer == LayerId.USER
    if (page == SettingsCategory.EXTENSIONS) {
        KitSection(null) {
            KitRow(stringResource(R.string.ext_manage_title), subtitle = stringResource(R.string.ext_manage_desc), onClick = host.onOpenExtensions, id = "settings-link:extensions")
        }
    }
    if (page == SettingsCategory.FILES && user) StorageSection(viewModel, ui, host)
    CategoryRows(pageSettings(page, env.settings), env)
    if (page == SettingsCategory.GIT && user) {
        val entries by viewModel.gitCredentialEntries.collectAsStateWithLifecycle()
        GitCredentialsSection(entries, viewModel::saveGitToken, viewModel::forgetGitHost)
    }
}

@Composable
internal fun DiagnosticsPage(onOpen: () -> Unit) {
    KitSection(null) {
        KitRow(stringResource(R.string.diag_settings_entry_title), subtitle = stringResource(R.string.diag_settings_entry_desc), onClick = onOpen, id = "settings-link:diagnostics")
    }
}

internal fun messageRes(m: SettingsMessage): Int = when (m) {
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
