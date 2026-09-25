package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.extensions.TimedLogEntry
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitTabs

internal const val TAB_DETAILS = 0
internal const val TAB_CONTRIBUTIONS = 1
internal const val TAB_CAPABILITIES = 2
private const val TAB_LOG = 3
private const val TAB_VERSIONS = 4

/** What the page shows about the open extension: its row, the list item derived from it and its registry state. */
internal class PageModel(
    val row: ExtensionRow,
    val item: ExtensionListItem,
    val revokedReason: String?,
    val updateTo: String?,
    val log: List<TimedLogEntry>,
)

private val TAB_LABELS = listOf(R.string.extui_tab_details, R.string.extui_tab_contributions, R.string.extui_tab_capabilities, R.string.extui_tab_log, R.string.extui_tab_versions)

/** The page as drawn: the compact header, the tab strip and the selected tab, in a column capped at the readable width. */
@Composable
internal fun ExtensionPageContent(model: PageModel, tab: Int, onTab: (Int) -> Unit, actions: PageActions, modifier: Modifier = Modifier) {
    val row = model.row
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = Kit.contentMax).fillMaxWidth(), contentPadding = PaddingValues(bottom = Kit.space.l)) {
            item { PageHeader(row, model.item, model.revokedReason, model.updateTo, actions) }
            item { KitTabs(TAB_LABELS.map { stringResource(it) }, tab, onTab, Modifier.padding(top = Kit.space.s)) }
            item {
                Column {
                    when (tab) {
                        TAB_DETAILS -> DetailsTab(row, row.contributions, actions)
                        TAB_CONTRIBUTIONS -> ContributionsTab(row, actions)
                        TAB_CAPABILITIES -> CapabilitiesTab(row, actions)
                        TAB_LOG -> LogSection(model.log, showId = false, flat = false, onClear = null)
                        TAB_VERSIONS -> VersionsTab(row, model.item, model.updateTo, actions)
                    }
                }
            }
        }
    }
}
