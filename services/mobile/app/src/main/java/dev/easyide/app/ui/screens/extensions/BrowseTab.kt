package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.extensions.registry.RegistryPolicy
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.kit.kitMono

/** What the Browse tab does with its rows; the panel wires these to the view model. */
internal class BrowseActions(
    val onQuery: (String) -> Unit,
    val onOpen: (BrowseItem) -> Unit,
    val onInstall: (BrowseItem) -> Unit,
)

/**
 * The Browse tab (registry-and-install.md sec 7): "no registry configured", each registry's
 * index age or error with its reason, search, and one row per extension with its install or
 * update action. Tapping a row opens the detail dialog.
 */
internal fun LazyListScope.browseTab(state: BrowseUiState, query: String, actions: BrowseActions) {
    if (!state.configured) {
        item { NoRegistry(state.problems) }
        return
    }
    items(state.problems) { KitBanner(stringResource(R.string.reg_config_problem, it), tone = Tone.Danger) }
    items(state.registries, key = { "reg-" + it.id }) { RegistryHeader(it) }
    item {
        KitField(
            query, actions.onQuery,
            Modifier.padding(horizontal = Kit.space.l, vertical = Kit.space.s),
            hint = stringResource(R.string.reg_search_hint),
        )
    }
    if (state.items.isEmpty()) {
        item { KitEmptyState(EmptyArt.Search, stringResource(R.string.reg_no_results)) }
    } else {
        item { KitSection(title = null) { state.items.forEach { BrowseRow(it, actions) } } }
    }
}

@Composable
private fun NoRegistry(problems: List<String>) {
    Column {
        KitEmptyState(EmptyArt.Offline, stringResource(R.string.reg_none_body))
        problems.forEach { KitBanner(stringResource(R.string.reg_config_problem, it), tone = Tone.Danger) }
    }
}

@Composable
private fun RegistryHeader(line: RegistryLine) {
    val age = line.ageMinutes
    val text = when {
        line.refreshing -> stringResource(R.string.reg_refreshing, line.id)
        age == null -> stringResource(R.string.reg_never_fetched, line.id)
        else -> stringResource(R.string.reg_age, line.id, ageText(age))
    }
    Column {
        BasicText(text, Modifier.padding(horizontal = Kit.space.l, vertical = Kit.space.s), style = Kit.type.bodySmall.kitMono().copy(color = Kit.colors.textMuted))
        if (line.stale) KitBanner(stringResource(R.string.reg_stale, RegistryPolicy.STALE_INDEX_WARN_DAYS.toInt()), tone = Tone.Warning)
        line.error?.let { KitBanner(registryErrorLine(it), tone = Tone.Danger) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowseRow(item: BrowseItem, actions: BrowseActions) {
    val action = item.action()
    Column {
        KitRow(
            title = item.id,
            mono = true,
            subtitle = item.description ?: item.displayName,
            onClick = { actions.onOpen(item) },
            id = "browse-row",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.s)) {
                    item.entry?.let { BasicText(it.version.toString(), style = Kit.type.bodySmall.kitMono().copy(color = Kit.colors.textMuted)) }
                    if (action == BrowseAction.Install || action == BrowseAction.Update) {
                        val label = if (action == BrowseAction.Install) R.string.reg_install else R.string.extui_update
                        KitButton(stringResource(label), { actions.onInstall(item) }, style = KitButtonStyle.Secondary)
                    }
                }
            },
        )
        FlowRow(
            Modifier.padding(start = Kit.space.l, end = Kit.space.l, bottom = Kit.space.s),
            horizontalArrangement = Arrangement.spacedBy(Kit.space.xs),
            verticalArrangement = Arrangement.spacedBy(Kit.space.xs),
        ) {
            item.registryId?.let { KitTag(stringResource(R.string.reg_badge, it)) }
            item.installedVersion?.let { KitTag(stringResource(R.string.reg_installed, it)) }
            if (item.updateAvailable) KitTag(stringResource(R.string.extui_tag_update), tone = Tone.Accent)
            if (item.entry == null) KitTag(stringResource(R.string.extui_tag_incompatible), tone = Tone.Warning)
        }
    }
}
