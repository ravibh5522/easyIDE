package dev.easyide.app.ui.screens.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import dev.easyide.app.ui.kit.KitGroup
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.Tone

/**
 * The list side of Settings (screens.md 5): safe-mode banner, search, the layer control, then the
 * categories, or the matching settings across categories while a query is typed, under its own title
 * row (the host adds no header). Selecting a category, or a result, reports its id through
 * [onSelect] and the shell opens `easyide://settings/<id>`; a result also marks its row on the
 * page. Panels list, the stage shows.
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
    val filter = SettingsFilter.parse(query)
    val textOf = rememberSettingText()
    val results = if (filter.isActive) {
        SettingsSearch.ranked(SettingsSearch.matching(ui.settings.schema.settings, filter, ui.settings, ui.tab.layer, SettingsSchema.managedElsewhere, textOf))
    } else {
        emptyList()
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = Kit.space.xxl)) {
        BasicText(
            stringResource(R.string.nav_settings),
            Modifier.padding(start = Kit.space.l, end = Kit.space.l, top = Kit.space.m).semantics { heading() },
            style = Kit.type.titleMedium.copy(color = Kit.colors.plainText),
        )
        system.safeMode?.let { reason ->
            KitBanner(
                text = stringResource(R.string.safe_mode_banner_text, stringResource(safeModeReasonRes(reason))),
                tone = Tone.Warning,
                action = KitAction(stringResource(R.string.safe_mode_exit)) { viewModel.exitSafeMode { activity?.recreate() } },
                modifier = Modifier.padding(start = Kit.space.l, end = Kit.space.l, top = Kit.space.l),
            )
        }
        KitField(
            value = query,
            onValueChange = viewModel::onQueryChanged,
            hint = stringResource(R.string.settings_search_hint),
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (filter.isActive) onSelect(SettingsCategory.SEARCH_ID) }),
            modifier = Modifier.padding(start = Kit.space.l, end = Kit.space.l, top = Kit.space.l),
        )
        SettingsLayerControl(
            ui.tab, ui.environments, ui.projects, viewModel::onTabSelected,
            Modifier.padding(start = Kit.space.l, end = Kit.space.l, top = Kit.space.m),
        )
        Column(Modifier.padding(start = Kit.space.l, end = Kit.space.l, top = Kit.space.l)) {
            when {
                !filter.isActive -> CategoryList(selected) { viewModel.onHighlight(null); onSelect(it) }
                results.isEmpty() -> KitEmptyState(
                    EmptyArt.Search, stringResource(R.string.settings_search_empty),
                    action = KitAction(stringResource(R.string.settings_search_clear)) { viewModel.onQueryChanged("") },
                )
                else -> KitGroup {
                    results.forEach { setting ->
                        val category = SettingsCategory.of(setting)
                        KitRow(
                            title = setting.title.resolve(),
                            subtitle = stringResource(category.title),
                            onClick = { viewModel.onHighlight(setting.key); onSelect(category.id) },
                            id = "settings-result:${setting.key}",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryList(selected: String?, onSelect: (String) -> Unit) {
    KitGroup {
        SettingsCategory.entries.forEach { category ->
            KitRow(
                title = stringResource(category.title),
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
