package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.data.settings.SafeModeReason
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.Tone

/** What the Installed tab does with its rows; the panel wires these to the view model. */
internal class InstalledActions(
    val onQuery: (String) -> Unit,
    val onSelect: (String) -> Unit,
    val onEnabled: (key: String, enabled: Boolean) -> Unit,
)

/** The Installed tab: a search field, user packs then built-ins, each row selectable and switchable. */
internal fun LazyListScope.installedTab(groups: ExtensionGroups, query: String, selectedId: String?, actions: InstalledActions) {
    item {
        KitField(
            query, actions.onQuery,
            Modifier.padding(horizontal = Kit.space.l, vertical = Kit.space.s),
            hint = stringResource(R.string.extui_search_installed),
        )
    }
    if (groups.isEmpty) {
        item { KitEmptyState(EmptyArt.Search, if (query.isBlank()) stringResource(R.string.extui_none_installed) else stringResource(R.string.extui_no_match, query)) }
        return
    }
    if (groups.installed.isEmpty() && query.isBlank()) {
        item { KitEmptyState(EmptyArt.Extensions, stringResource(R.string.extui_none_installed)) }
    }
    if (groups.installed.isNotEmpty()) item { ExtensionSection(R.string.extui_section_installed, groups.installed, selectedId, actions) }
    if (groups.builtIn.isNotEmpty()) item { ExtensionSection(R.string.extui_section_builtin, groups.builtIn, selectedId, actions) }
}

@Composable
private fun ExtensionSection(title: Int, items: List<ExtensionListItem>, selectedId: String?, actions: InstalledActions) {
    KitSection(stringResource(title)) {
        items.forEach { item ->
            ExtensionListRow(item, selected = item.id == selectedId, onSelect = { actions.onSelect(item.id) }, onEnabled = { actions.onEnabled(item.key, it) })
        }
    }
}

/** Safe mode, then packs waiting on approval, then revoked packs: each one sentence with its next step. */
@Composable
internal fun PanelBanners(safeMode: SafeModeReason?, suspects: List<String>, attention: Attention, onExitSafeMode: () -> Unit, onSelect: (String) -> Unit) {
    Column {
        if (safeMode != null) {
            val reason = stringResource(when (safeMode) {
                SafeModeReason.SETTING -> R.string.ext_safe_mode_setting
                SafeModeReason.LAUNCHER_SHORTCUT -> R.string.ext_safe_mode_launcher
                SafeModeReason.AUTO_CRASH -> R.string.ext_safe_mode_auto
            })
            val text = if (suspects.isEmpty()) reason else reason + " " + stringResource(R.string.ext_safe_mode_suspects, suspects.joinToString())
            KitBanner(text, tone = Tone.Warning, action = KitAction(stringResource(R.string.ext_safe_mode_exit), onExitSafeMode))
        }
        attention.needsApproval.firstOrNull()?.let { first ->
            val n = attention.needsApproval.size
            KitBanner(pluralStringResource(R.plurals.extui_needs_approval, n, n), tone = Tone.Warning, action = KitAction(stringResource(R.string.extui_review)) { onSelect(first.id) })
        }
        attention.revoked.firstOrNull()?.let { first ->
            val n = attention.revoked.size
            KitBanner(pluralStringResource(R.plurals.extui_revoked, n, n), tone = Tone.Danger, action = KitAction(stringResource(R.string.extui_review)) { onSelect(first.id) })
        }
    }
}
