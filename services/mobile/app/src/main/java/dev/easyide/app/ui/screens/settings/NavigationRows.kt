package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.KitToggle
import dev.easyide.app.ui.kit.ToggleKind
import dev.easyide.app.ui.props.ShellSettingsSchema
import dev.easyide.app.ui.shell.ShellScope

/**
 * The navigation items in the order the shell shows them, per scope: tap a row to show or hide it,
 * pin it to keep it among the visible cells of a compact bar, move it with the arrows (drag-free,
 * so it works from a keyboard and a screen reader). An item an extension added names its pack. The
 * three keys are user-level; in another layer the rows are read-only.
 */
@Composable
internal fun NavigationRows(ctx: SettingsContext, catalog: LayoutCatalog) {
    val order = ctx.snapshot[ShellSettingsSchema.navigationOrder]
    val hidden = ctx.snapshot[ShellSettingsSchema.navigationHidden]
    val pinned = ctx.snapshot[ShellSettingsSchema.navigationPinned]
    val editable = RowState.blockOf(ShellSettingsSchema.navigationOrder, ctx.layer, ctx.language) == null
    val groups = ShellScope.entries.map { scope ->
        val entries = catalog.navigation.filter { it.scope == scope }
        scope to NavOrdering.effective(entries.map { it.id }, order).map { id -> entries.first { it.id == id } }
    }
    fun writeOrder(scope: ShellScope, ids: List<String>) {
        val all = groups.flatMap { (s, entries) -> if (s == scope) ids else entries.map { it.id } }.distinct()
        ctx.actions.set(ShellSettingsSchema.navigationOrder, all, ctx.language)
    }

    groups.filter { (_, entries) -> entries.isNotEmpty() }.forEach { (scope, entries) ->
        val ids = entries.map { it.id }
        KitSection(stringResource(if (scope == ShellScope.APP) R.string.layout_nav_app else R.string.layout_nav_workspace), count = entries.size, collapsible = true) {
            entries.forEach { entry ->
                val visible = entry.id !in hidden
                KitRow(
                    title = entry.title,
                    subtitle = listOfNotNull(entry.pack, if (visible) null else stringResource(R.string.layout_hidden)).joinToString(" - ").ifEmpty { null },
                    leading = { KitToggle(visible, null, kind = ToggleKind.Check, enabled = editable) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
                            KitTag(
                                stringResource(R.string.layout_pinned), selected = entry.id in pinned,
                                onClick = if (editable) ({ ctx.actions.set(ShellSettingsSchema.navigationPinned, NavOrdering.toggled(pinned, entry.id), ctx.language) }) else null,
                            )
                            KitIconButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.layout_move_up, entry.title), { writeOrder(scope, NavOrdering.moved(ids, entry.id, -1)) }, enabled = editable && entry.id != ids.first())
                            KitIconButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.layout_move_down, entry.title), { writeOrder(scope, NavOrdering.moved(ids, entry.id, 1)) }, enabled = editable && entry.id != ids.last())
                        }
                    },
                    onClick = if (editable) ({ ctx.actions.set(ShellSettingsSchema.navigationHidden, NavOrdering.toggled(hidden, entry.id), ctx.language) }) else null,
                    id = "nav-item:${entry.id}",
                )
            }
        }
    }
}
