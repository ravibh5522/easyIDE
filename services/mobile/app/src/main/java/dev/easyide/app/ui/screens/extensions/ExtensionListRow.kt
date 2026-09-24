package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.KitToggle
import dev.easyide.extensions.manifest.Source

/**
 * One installed extension on one line (density.md 1): `name  id@version ...... [state or source] switch`.
 * The id and version are the muted inline description and give way to the name; the most urgent state
 * tag or, when there is none, the source (built-ins say it in their section, so no tag) and the enable
 * switch sit at the end; the page has the rest, so a 260dp panel keeps room for the name.
 * Tapping the row selects it; the switch only toggles.
 */
@Composable
internal fun ExtensionListRow(item: ExtensionListItem, selected: Boolean, onSelect: () -> Unit, onEnabled: (Boolean) -> Unit) {
    KitRow(
        title = item.name,
        subtitle = "${item.id}@${item.version}",
        selected = selected,
        onClick = onSelect,
        id = "extension-row",
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
                val state = item.tags.firstOrNull()
                if (state != null) KitTag(tagText(state), tone = state.tone) else if (item.source != Source.BUILT_IN) KitTag(sourceTag(item.source))
                if (item.toggleable) KitToggle(item.enabled, onEnabled, Modifier.padding(start = Kit.space.xs))
            }
        },
    )
}
