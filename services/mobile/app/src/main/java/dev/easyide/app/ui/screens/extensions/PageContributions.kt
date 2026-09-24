package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitToggle
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.kit.Twistie
import dev.easyide.app.ui.components.MonoText

/**
 * The Contributions tab: what the extension adds, read from the runtime's contribution registry,
 * grouped by kind with a count. A group opens to its entries, each of which can be hidden or
 * shown and, where it has an order, moved (customization.md 3.3).
 */
@Composable
internal fun ContributionsTab(row: ExtensionRow, actions: PageActions) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = groupContributions(row.contributions)
    if (groups.isEmpty()) {
        KitEmptyState(EmptyArt.Prompt, stringResource(R.string.ext_contributions_none))
    } else {
        KitSection(stringResource(R.string.ext_contributions), count = row.contributions.size, collapsible = true) {
            groups.forEach { (group, lines) ->
                val expanded = open == group.name
                KitRow(
                    title = stringResource(group.title),
                    twistie = if (expanded) Twistie.Expanded else Twistie.Collapsed,
                    onClick = { open = if (expanded) null else group.name },
                    id = "contribution-group",
                    trailing = { MonoText(lines.size.toString()) },
                )
                if (expanded) lines.forEach { ContributionLine(it, actions) }
            }
        }
    }
    row.shadowed.forEach { KitBanner(it.message, Modifier.padding(top = Kit.space.m), Tone.Warning) }
}

/** One contribution as a sub row of its group: its ref, why it is hidden or in conflict inline, an order control and the show switch. */
@Composable
private fun ContributionLine(line: InspectorLine, actions: PageActions) {
    val switchable = line.hideable || line.hidden
    val notes = buildList {
        line.hiddenBy?.let { add(stringResource(R.string.ext_hidden_by, it)) }
        if (!line.hideable) add(stringResource(R.string.ext_not_hideable))
        line.conflicts.forEach { add(it.message) }
    }
    KitRow(
        title = line.ref.substringAfter(':'),
        mono = true,
        subtitle = notes.takeIf { it.isNotEmpty() }?.joinToString(stringResource(R.string.home_separator)),
        level = 1,
        onClick = if (switchable) ({ actions.onHide(line, !line.hidden) }) else null,
        id = "contribution-line",
        trailing = { KitToggle(!line.hidden, onCheckedChange = null, enabled = switchable) },
        actions = if (line.location != null) {
            {
                KitIconButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.ext_move_up), { actions.onMove(line, -1) }, enabled = line.canMoveUp)
                KitIconButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.ext_move_down), { actions.onMove(line, 1) }, enabled = line.canMoveDown)
            }
        } else null,
    )
}
