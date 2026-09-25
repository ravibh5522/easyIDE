package dev.easyide.app.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.shell.LayoutPresets
import dev.easyide.app.ui.shell.LayoutPreset
import dev.easyide.sandbox.external.ExternalFolderSync

/**
 * What the shell gives a settings page beyond the ViewModel: the folder picker's backing service,
 * the two destinations a page links to, where one-shot messages go (the shell's toast), and what
 * the shell offers for rearranging (its navigation items, containers and layout presets).
 */
class SettingsHost(
    val externalFolderSync: ExternalFolderSync,
    val onOpenExtensions: () -> Unit,
    val onOpenDiagnostics: () -> Unit,
    val onNotify: (String) -> Unit,
    val layout: LayoutCatalog = LayoutCatalog.core(),
    val presets: List<LayoutPreset> = LayoutPresets.BUILT_IN,
)

/**
 * One settings page, the document `easyide://settings/<category>` (screens.md 5): [category] is a
 * [SettingsCategory.id] or [SettingsCategory.SEARCH_ID]. Scaffold-free content: the shell supplies
 * the tab, the title and the back stack. Rows are generated from the schema; Appearance, Layout,
 * Keyboard, Language servers, Sandbox, Diagnostics and Advanced add controls of their own.
 */
@Composable
fun SettingsPage(
    viewModel: SettingsViewModel,
    category: String,
    host: SettingsHost,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val themeCards by viewModel.themeCards.collectAsStateWithLifecycle()
    val keyRows by viewModel.keyRows.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val highlight by viewModel.highlight.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val errorDetail by viewModel.errorDetail.collectAsStateWithLifecycle()

    val messageText = message?.let { stringResource(messageRes(it)) } ?: errorDetail
    LaunchedEffect(messageText) {
        val text = messageText ?: return@LaunchedEffect
        host.onNotify(text)
        viewModel.onMessageShown()
    }

    val filter = SettingsFilter.parse(query)
    val ctx = remember(ui.settings, ui.tab, filter.language, language, highlight, viewModel) {
        SettingsContext(ui.settings, ui.tab.layer, filter.language ?: language.ifBlank { null }, viewModel, viewModel::openJson, highlight)
    }
    val env = PageEnv(ctx, ui.settings.schema.settings, themeCards, viewModel, keyRows)
    val page = SettingsCategory.ofId(category)

    SettingsPageFrame(modifier) {
        if (page == null && category != SettingsCategory.SEARCH_ID) {
            KitEmptyState(EmptyArt.Prompt, stringResource(R.string.settings_page_unknown, category))
        } else {
            PageHeader(viewModel, ui, ctx, page, env)
            PageBody(viewModel, ui, env, host, page, filter)
        }
    }
    PageDialogs(viewModel)
}

@Composable
private fun PageBody(
    viewModel: SettingsViewModel,
    ui: SettingsUiState,
    env: PageEnv,
    host: SettingsHost,
    page: SettingsCategory?,
    filter: SettingsFilter,
) {
    when (page) {
        null -> SearchResultsPage(env, filter, ui)
        SettingsCategory.APPEARANCE -> AppearancePage(env)
        SettingsCategory.LAYOUT -> LayoutPage(env, host)
        SettingsCategory.KEYBOARD -> KeyboardPage(viewModel)
        SettingsCategory.LANGUAGE_SERVERS -> LanguageServersPage(viewModel, env, ui.tab)
        SettingsCategory.SANDBOX -> SandboxPage(viewModel, ui)
        SettingsCategory.DIAGNOSTICS -> DiagnosticsPage(host.onOpenDiagnostics)
        SettingsCategory.ADVANCED -> AdvancedPage(viewModel, ui)
        SettingsCategory.EDITOR, SettingsCategory.TERMINAL, SettingsCategory.FILES,
        SettingsCategory.GIT, SettingsCategory.EXTENSIONS -> CategoryPage(viewModel, page, ui, env, host)
    }
}
