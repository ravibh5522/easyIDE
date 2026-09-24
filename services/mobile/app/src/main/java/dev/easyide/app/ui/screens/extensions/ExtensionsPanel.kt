package dev.easyide.app.ui.screens.extensions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.app.ui.kit.KitTabs

private const val TAB_INSTALLED = 0
private const val TAB_BROWSE = 1
private const val ANY_MIME = "*/*"

/**
 * The Extensions panel (screens.md 4), scaffold-free content for the shell to host: Installed and
 * Browse tabs, search, one row per pack, banners for safe mode and packs that need the person,
 * the Extension Log, and the install entry. [selectedId] marks the row whose page is open;
 * [onSelect] asks the shell to open `easyide://extension/<id>`. Dialogs are not part of the panel:
 * mount [ExtensionsDialogs] once wherever the panel or a page is live.
 */
@Composable
fun ExtensionsPanel(selectedId: String?, onSelect: (String) -> Unit, viewModel: ExtensionsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(TAB_INSTALLED) }
    var installedQuery by rememberSaveable { mutableStateOf("") }
    // A local copy of the text: the view model's query comes back through a flow, which lags a keystroke.
    var browseQuery by rememberSaveable { mutableStateOf(browse.query) }
    val items = remember(state.rows, browse.updates, browse.revoked) {
        state.rows.map { it.toItem(browse.updates[it.id], browse.revoked["${it.id}@${it.pkg.directory.name}"]) }
    }
    val groups = remember(items, installedQuery) { groupItems(filterItems(items, installedQuery)) }
    val attention = remember(items) { attention(items) }
    val installed = InstalledActions(
        onQuery = { installedQuery = it },
        onSelect = onSelect,
        onEnabled = { key, on -> state.rows.firstOrNull { it.key == key }?.let { viewModel.setEnabled(it, on) } },
    )
    val browsing = BrowseActions(
        onQuery = { browseQuery = it; viewModel.setQuery(it) },
        onOpen = viewModel::openDetail,
        onInstall = viewModel::installFromRegistry,
    )

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Kit.space.l)) {
        item {
            PanelHeader(showRefresh = tab == TAB_BROWSE && browse.configured, onRefresh = viewModel::refreshRegistries, viewModel = viewModel, onBrowse = { tab = TAB_BROWSE })
        }
        item { PanelBanners(state.safeMode, state.safeModeSuspects.map { it.value }, attention, viewModel::exitSafeMode, onSelect) }
        item {
            val browseLabel = if (browse.updates.isEmpty()) stringResource(R.string.ext_tab_browse) else stringResource(R.string.extui_tab_browse_updates, browse.updates.size)
            KitTabs(listOf(stringResource(R.string.ext_tab_installed), browseLabel), tab, { tab = it })
        }
        if (tab == TAB_INSTALLED) {
            installedTab(groups, installedQuery, selectedId, installed)
            item { LogSection(state.log, showId = true, onClear = viewModel::clearLog) }
        } else {
            browseTab(browse, browseQuery, browsing)
        }
    }
}

/** The title, the registry refresh (Browse only) and `+`: the install entry, one small menu. */
@Composable
private fun PanelHeader(showRefresh: Boolean, onRefresh: () -> Unit, viewModel: ExtensionsViewModel, onBrowse: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::stageArchive) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(viewModel::stageFolder) }
    Row(
        Modifier.fillMaxWidth().padding(start = Kit.space.l, end = Kit.space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Kit.space.xs),
    ) {
        BasicText(
            stringResource(R.string.ext_screen_title),
            Modifier.weight(1f).semantics { heading() },
            style = Kit.type.titleMedium.copy(color = Kit.colors.plainText),
        )
        if (showRefresh) KitIconButton(Icons.Filled.Refresh, stringResource(R.string.reg_refresh), onRefresh)
        Box {
            KitIconButton(Icons.Filled.Add, stringResource(R.string.ext_install), { menuOpen = true })
            KitMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                items = listOf(
                    KitMenuItem.Action(stringResource(R.string.extui_install_registry), onBrowse),
                    KitMenuItem.Action(stringResource(R.string.extui_install_file), { pickFile.launch(arrayOf(ANY_MIME)) }),
                    KitMenuItem.Action(stringResource(R.string.extui_install_folder), { pickFolder.launch(null) }),
                    KitMenuItem.Action(stringResource(R.string.extui_install_sample), viewModel::openSamples),
                    KitMenuItem.Divider,
                    KitMenuItem.Action(stringResource(R.string.create_ext_action), viewModel::openCreate),
                ),
            )
        }
    }
}
