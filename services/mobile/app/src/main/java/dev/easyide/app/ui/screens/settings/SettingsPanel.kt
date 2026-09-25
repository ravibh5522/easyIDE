package dev.easyide.app.ui.screens.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.data.settings.SafeModeReason
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitSectionHeader
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.kit.Twistie

/**
 * The list side of Settings (screens.md 5): safe-mode banner, search, the layer control, then the
 * categories, or the matching settings across categories while a query is typed. Selecting a
 * category, or a result, reports its id through [onSelect] and the shell opens
 * `easyide://settings/<id>`; a result also marks its row on the page. Panels list, the stage shows.
 */
@Composable
fun SettingsPanel(
    viewModel: SettingsViewModel,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val system by viewModel.systemState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    SettingsPanelContent(
        ui = ui, safeMode = system.safeMode, query = query, selected = selected,
        onQuery = viewModel::onQueryChanged,
        onExitSafeMode = { viewModel.exitSafeMode { activity?.recreate() } },
        onTab = viewModel::onTabSelected,
        onSelect = { id, key -> viewModel.onHighlight(key); onSelect(id) },
        modifier = modifier,
    )
}

/** [onSelect] reports the page id and the key of the result to mark on it (null when a category was picked). */
@Composable
internal fun SettingsPanelContent(
    ui: SettingsUiState,
    safeMode: SafeModeReason?,
    query: String,
    selected: String?,
    onQuery: (String) -> Unit,
    onExitSafeMode: () -> Unit,
    onTab: (LayerTab) -> Unit,
    onSelect: (id: String, key: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filter = SettingsFilter.parse(query)
    val textOf = rememberSettingText()
    val results = if (filter.isActive) {
        SettingsSearch.ranked(SettingsSearch.matching(ui.settings.schema.settings, filter, ui.settings, ui.tab.layer, SettingsSchema.managedElsewhere, textOf))
    } else {
        emptyList()
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = Kit.space.xxl)) {
        KitSectionHeader(stringResource(R.string.nav_settings))
        safeMode?.let { reason ->
            KitBanner(
                text = stringResource(R.string.safe_mode_banner_text, stringResource(safeModeReasonRes(reason))),
                tone = Tone.Warning,
                action = KitAction(stringResource(R.string.safe_mode_exit), onExitSafeMode),
                modifier = Modifier.pageGutter(),
            )
        }
        KitField(
            value = query,
            onValueChange = onQuery,
            hint = stringResource(R.string.settings_search_hint),
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (filter.isActive) onSelect(SettingsCategory.SEARCH_ID, null) }),
            modifier = Modifier.pageGutter(),
        )
        SettingsLayerControl(ui.tab, ui.environments, ui.projects, onTab, Modifier.pageGutter())
        Column(Modifier.padding(top = Kit.space.s)) {
            when {
                !filter.isActive -> CategoryList(selected) { onSelect(it, null) }
                results.isEmpty() -> KitEmptyState(
                    EmptyArt.Search, stringResource(R.string.settings_search_empty),
                    action = KitAction(stringResource(R.string.settings_search_clear)) { onQuery("") },
                )
                else -> KitSection(stringResource(R.string.settings_results_title), count = results.size, flat = true) {
                    results.forEach { setting ->
                        val category = SettingsCategory.of(setting)
                        KitRow(
                            title = setting.title.resolve(),
                            subtitle = stringResource(category.title),
                            twistie = Twistie.Leaf,
                            onClick = { onSelect(category.id, setting.key) },
                            id = "settings-result:${setting.key}",
                        )
                    }
                }
            }
        }
    }
}

/** One line per page, no card: the block marker shows where you are. The leaf twistie column lines the names up under the header's title. */
@Composable
private fun CategoryList(selected: String?, onSelect: (String) -> Unit) {
    KitSection(null, flat = true) {
        SettingsCategory.entries.forEach { category ->
            KitRow(
                title = stringResource(category.title),
                twistie = Twistie.Leaf,
                selected = category.id == selected,
                onClick = { onSelect(category.id) },
                id = "settings-category:${category.id}",
            )
        }
    }
}

internal fun safeModeReasonRes(r: SafeModeReason): Int = when (r) {
    SafeModeReason.SETTING -> R.string.safe_mode_reason_setting
    SafeModeReason.LAUNCHER_SHORTCUT -> R.string.safe_mode_reason_shortcut
    SafeModeReason.AUTO_CRASH -> R.string.safe_mode_reason_crash
}
