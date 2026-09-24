package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.KitToggle

/**
 * One installed extension (screens.md 4): mono id, tabular version, one line of description,
 * the enable toggle at the end, and under it the source tag and the state tags. Tapping the
 * row selects it; the toggle only toggles.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExtensionListRow(item: ExtensionListItem, selected: Boolean, onSelect: () -> Unit, onEnabled: (Boolean) -> Unit) {
    Column {
        KitRow(
            title = item.id,
            mono = true,
            subtitle = item.description ?: item.name,
            selected = selected,
            onClick = onSelect,
            id = "extension-row",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.s)) {
                    BasicText(item.version, style = Kit.text.monoSmall.copy(color = Kit.colors.textMuted))
                    if (item.toggleable) KitToggle(item.enabled, onEnabled)
                }
            },
        )
        FlowRow(
            Modifier.padding(start = Kit.space.l, end = Kit.space.l, bottom = Kit.space.s),
            horizontalArrangement = Arrangement.spacedBy(Kit.space.xs),
            verticalArrangement = Arrangement.spacedBy(Kit.space.xs),
        ) {
            KitTag(sourceTag(item.source))
            item.tags.forEach { KitTag(tagText(it), tone = it.tone) }
        }
    }
}
