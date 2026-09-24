package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import dev.easyide.app.ui.kit.PromptGlyph
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.kit.kitMono

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
        KitSection(stringResource(R.string.ext_contributions)) {
            groups.forEach { (group, lines) ->
                val expanded = open == group.name
                KitRow(
                    title = stringResource(group.title),
                    onClick = { open = if (expanded) null else group.name },
                    leading = { if (expanded) PromptGlyph(color = Kit.colors.accent) },
                    id = "contribution-group",
                    trailing = { BasicText(lines.size.toString(), style = Kit.type.bodySmall.kitMono().copy(color = Kit.colors.textMuted)) },
                )
                if (expanded) lines.forEach { ContributionLine(it, actions) }
            }
        }
    }
    row.shadowed.forEach { KitBanner(it.message, Modifier.padding(top = Kit.space.m), Tone.Warning) }
}

/** One contribution: its ref, why it is hidden or in conflict, an order control and the show switch. */
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
        subtitle = notes.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        onClick = if (switchable) ({ actions.onHide(line, !line.hidden) }) else null,
        id = "contribution-line",
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
                if (line.location != null) {
                    KitIconButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.ext_move_up), { actions.onMove(line, -1) }, enabled = line.canMoveUp)
                    KitIconButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.ext_move_down), { actions.onMove(line, 1) }, enabled = line.canMoveDown)
                }
                KitToggle(!line.hidden, onCheckedChange = null, enabled = switchable)
            }
        },
    )
}
