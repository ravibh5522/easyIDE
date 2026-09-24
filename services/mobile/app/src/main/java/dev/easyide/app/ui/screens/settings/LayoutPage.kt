package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitChoice
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitToggle
import dev.easyide.app.ui.kit.ToggleKind
import dev.easyide.app.ui.props.ShellSettingsSchema
import dev.easyide.app.ui.shell.LayoutPresets
import dev.easyide.app.ui.shell.Placement
import kotlinx.serialization.json.JsonObject

/**
 * The Layout page (screens.md 5): the arrangement preset, where and how the navigation shows, the
 * order and visibility of navigation items, and where each container's panel sits. Every key is a
 * `shell.*` setting; the shell reads them, so a change applies at once. Only the preset can be set
 * by a project, so a cloned repository cannot rearrange the app.
 */
@Composable
internal fun LayoutPage(env: PageEnv, host: SettingsHost) {
    val ctx = env.ctx
    KitSection(stringResource(R.string.layout_arrangement_section), count = ARRANGEMENT_ROWS, collapsible = true) {
        PresetRow(ctx, host)
        SettingRow(ShellSettingsSchema.navigationPosition, ctx, inlineChoice = true)
        SettingRow(ShellSettingsSchema.navigationLabels, ctx, inlineChoice = true)
    }
    NavigationRows(ctx, host.layout)
    ContainerRows(ctx, host.layout)
}

/** The preset and the two navigation settings the first section lists. */
private const val ARRANGEMENT_ROWS = 3

/** `shell.layout.preset`: auto, then every preset the shell offers; a preset that is gone stays visible so the choice is not lost. */
@Composable
private fun PresetRow(ctx: SettingsContext, host: SettingsHost) {
    val setting = ShellSettingsSchema.layoutPreset
    val current = ctx.snapshot[setting]
    val ids = listOf(LayoutPresets.AUTO) + host.presets.map { it.id }
    val autoLabel = stringResource(R.string.layout_preset_auto)
    val missing = current.takeIf { it !in ids }
    val labels = listOf(autoLabel) + host.presets.map { it.title } + listOfNotNull(missing?.let { stringResource(R.string.layout_preset_missing, it) })
    val all = ids + listOfNotNull(missing)
    val state = RowState.of(setting, ctx.snapshot, ctx.layer, ctx.language)
    PickerRow(
        id = setting.key,
        title = setting.title.resolve(),
        subtitle = setting.description.resolve(),
        labels = labels,
        selected = all.indexOf(current),
        modified = state.modifiedHere,
        onReset = { ctx.actions.reset(setting, null) },
        onPick = { i -> if (state.editable) all[i].let { if (it == LayoutPresets.AUTO) ctx.actions.reset(setting, null) else ctx.actions.set(setting, it, null) } },
    )
}

/** Where each container's panel sits and whether it is shown; the tap opens the choice under the row. */
@Composable
private fun ContainerRows(ctx: SettingsContext, catalog: LayoutCatalog) {
    if (catalog.containers.isEmpty()) return
    val placement = ctx.snapshot[ShellSettingsSchema.containerPlacement] as? JsonObject ?: JsonObject(emptyMap())
    val hidden = ctx.snapshot[ShellSettingsSchema.containersHidden]
    val placed = ShellSettingsSchema.placementMap(placement)
    val editable = RowState.blockOf(ShellSettingsSchema.containerPlacement, ctx.layer, ctx.language) == null
    KitSection(stringResource(R.string.layout_panels_section), count = catalog.containers.size, collapsible = true) {
        catalog.containers.forEach { c ->
            var open by rememberSaveable(c.id) { mutableStateOf(false) }
            val at = placed[c.id]?.takeIf { it in c.allowed } ?: c.placement
            KitRow(
                title = c.title,
                subtitle = c.pack,
                leading = { KitToggle(c.id !in hidden, null, kind = ToggleKind.Check, enabled = editable) },
                trailing = { ValueText(stringResource(placementLabel(at))) },
                onClick = if (editable) ({ open = !open }) else null,
                id = "container:${c.id}",
            )
            if (open && editable) RowBlock(underLeading = true) {
                val labels = c.allowed.associateWith { stringResource(placementLabel(it)) }
                KitChoice(
                    c.allowed, at, { labels.getValue(it) },
                    { ctx.actions.setJson(ShellSettingsSchema.containerPlacement, PlacementEdit.with(placement, c.id, it, c.placement), ctx.language) },
                )
                Row(Modifier.padding(top = Kit.space.s), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        stringResource(if (c.id in hidden) R.string.layout_show_panel else R.string.layout_hide_panel),
                        Modifier.padding(end = Kit.space.s),
                        style = Kit.text.body.copy(color = Kit.colors.plainText),
                    )
                    KitToggle(c.id !in hidden, { ctx.actions.set(ShellSettingsSchema.containersHidden, NavOrdering.toggled(hidden, c.id), ctx.language) })
                }
            }
        }
    }
}

private fun placementLabel(p: Placement): Int = when (p) {
    Placement.SIDEBAR -> R.string.layout_placement_sidebar
    Placement.SECONDARY_SIDEBAR -> R.string.layout_placement_secondary
    Placement.PANEL -> R.string.layout_placement_panel
}
