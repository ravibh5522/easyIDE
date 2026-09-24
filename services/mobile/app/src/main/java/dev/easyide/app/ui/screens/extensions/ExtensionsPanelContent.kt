package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.app.ui.kit.KitTabs

private const val TAB_INSTALLED = 0
private const val TAB_BROWSE = 1

/** Everything the panel asks of its owner, so [ExtensionsPanelContent] holds no view model. */
internal class PanelActions(
    val onSelect: (String) -> Unit,
    val onEnabled: (key: String, enabled: Boolean) -> Unit,
    val browse: BrowseActions,
    val onRefresh: () -> Unit,
    val onExitSafeMode: () -> Unit,
    val onClearLog: () -> Unit,
    val onPickFile: () -> Unit,
    val onPickFolder: () -> Unit,
    val onCreate: () -> Unit,
)

/** The panel as drawn: header, banners, the dense tab strip, then the Installed or Browse list. */
@Composable
internal fun ExtensionsPanelContent(state: ExtensionsUiState, browse: BrowseUiState, selectedId: String?, actions: PanelActions, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_INSTALLED) }
    var installedQuery by rememberSaveable { mutableStateOf("") }
    // A local copy of the text: the view model's query comes back through a flow, which lags a keystroke.
    var browseQuery by rememberSaveable { mutableStateOf(browse.query) }
    val items = remember(state.rows, browse.updates, browse.revoked) {
        state.rows.map { it.toItem(browse.updates[it.id], browse.revoked["${it.id}@${it.pkg.directory.name}"]) }
    }
    val groups = remember(items, installedQuery) { groupItems(filterItems(items, installedQuery)) }
    val attention = remember(items) { attention(items) }
    val installed = InstalledActions(onQuery = { installedQuery = it }, onSelect = actions.onSelect, onEnabled = actions.onEnabled)
    val browsing = BrowseActions(
        onQuery = { browseQuery = it; actions.browse.onQuery(it) },
        onOpen = actions.browse.onOpen,
        onInstall = actions.browse.onInstall,
    )

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Kit.space.l)) {
        item { PanelHeader(showRefresh = tab == TAB_BROWSE && browse.configured, actions = actions, onBrowse = { tab = TAB_BROWSE }) }
        item { PanelBanners(state.safeMode, state.safeModeSuspects.map { it.value }, attention, actions.onExitSafeMode, actions.onSelect) }
        item {
            val browseLabel = if (browse.updates.isEmpty()) stringResource(R.string.ext_tab_browse) else stringResource(R.string.extui_tab_browse_updates, browse.updates.size)
            KitTabs(listOf(stringResource(R.string.ext_tab_installed), browseLabel), tab, { tab = it }, height = Kit.control.panelTabHeight)
        }
        if (tab == TAB_INSTALLED) {
            installedTab(groups, installedQuery, selectedId, installed)
            item { LogSection(state.log, showId = true, flat = true, onClear = actions.onClearLog) }
        } else {
            browseTab(browse, browseQuery, browsing)
        }
    }
}

/** The title in the caps of a panel header, the registry refresh (Browse only) and `+`: the install entry, one small menu. */
@Composable
private fun PanelHeader(showRefresh: Boolean, actions: PanelActions, onBrowse: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = Kit.control.panelTabHeight).padding(start = Kit.control.hPad, end = Kit.space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Kit.space.xs),
    ) {
        BasicText(
            stringResource(R.string.ext_screen_title).uppercase(),
            Modifier.weight(1f).semantics { heading() },
            style = Kit.text.label.copy(color = Kit.colors.textMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showRefresh) KitIconButton(Icons.Filled.Refresh, stringResource(R.string.reg_refresh), actions.onRefresh)
        Box {
            KitIconButton(Icons.Filled.Add, stringResource(R.string.ext_install), { menuOpen = true })
            KitMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                items = listOf(
                    KitMenuItem.Action(stringResource(R.string.extui_install_registry), onBrowse),
                    KitMenuItem.Action(stringResource(R.string.extui_install_file), actions.onPickFile),
                    KitMenuItem.Action(stringResource(R.string.extui_install_folder), actions.onPickFolder),
                    KitMenuItem.Divider,
                    KitMenuItem.Action(stringResource(R.string.create_ext_action), actions.onCreate),
                ),
            )
        }
    }
}
