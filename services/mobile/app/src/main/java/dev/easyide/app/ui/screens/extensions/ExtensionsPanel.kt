package dev.easyide.app.ui.screens.extensions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val ANY_MIME = "*/*"

/**
 * The Extensions panel (screens.md 4), scaffold-free content for the shell to host: Installed and
 * Browse tabs, search, one row per pack, banners for safe mode and packs that need the person,
 * the Extension Log, and the install entry. [selectedId] marks the row whose page is open;
 * [onSelect] asks the shell to open `easyide://extension/<id>`. Dialogs are not part of the panel:
 * mount [ExtensionsDialogs] once wherever the panel or a page is live. This binds the view model;
 * what is drawn is [ExtensionsPanelContent].
 */
@Composable
fun ExtensionsPanel(selectedId: String?, onSelect: (String) -> Unit, viewModel: ExtensionsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::stageArchive) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(viewModel::stageFolder) }
    val actions = PanelActions(
        onSelect = onSelect,
        onEnabled = { key, on -> state.rows.firstOrNull { it.key == key }?.let { viewModel.setEnabled(it, on) } },
        browse = BrowseActions(onQuery = viewModel::setQuery, onOpen = viewModel::openDetail, onInstall = viewModel::installFromRegistry),
        onRefresh = viewModel::refreshRegistries,
        onExitSafeMode = viewModel::exitSafeMode,
        onClearLog = viewModel::clearLog,
        onPickFile = { pickFile.launch(arrayOf(ANY_MIME)) },
        onPickFolder = { pickFolder.launch(null) },
        onSamples = viewModel::openSamples,
        onCreate = viewModel::openCreate,
    )
    ExtensionsPanelContent(state, browse, selectedId, actions, modifier)
}
