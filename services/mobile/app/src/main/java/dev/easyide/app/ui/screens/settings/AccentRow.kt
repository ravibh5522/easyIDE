package dev.easyide.app.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.easyide.app.R
import dev.easyide.app.data.settings.AppearanceSettingsSchema
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.props.AccentChoice

/**
 * `appearance.accent`: from the theme, from the wallpaper (Android 12+), one of the swatches of
 * identity.md 4, or a typed hex colour. A typed colour is shown as the interface will use it: when the
 * contrast guard had to move it, the field says "adjusted" and shows the result; when its hue sits near
 * a state colour it warns and still applies (properties.md 3). A project or environment layer may set
 * it (as a label colour for the environment, never a boundary); the guard applies in every layer.
 */
@Composable
internal fun AccentRow(ctx: SettingsContext) {
    val setting = AppearanceSettingsSchema.accent
    val state = RowState.of(setting, ctx.snapshot, ctx.layer, ctx.language)
    val current = ctx.snapshot[setting]
    val choice = AccentChoice.parse(current) ?: AccentChoice.Theme
    val colors = Kit.colors
    val ink = colors.plainText

    KitRow(
        title = setting.title.resolve(),
        subtitle = setting.description.resolve(),
        leading = { ModifiedDot(state.modifiedHere) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
                if (state.modifiedHere && state.editable) KitIconButton(Icons.Filled.Restore, stringResource(R.string.setting_reset), { ctx.actions.reset(setting, ctx.language) })
                ValueText(current)
            }
        },
        enabled = state.editable,
        id = "setting:${setting.key}",
    )
    state.block?.let { RowNote(stringResource(R.string.setting_not_in_layer)) }
    RowBlock {
        FlowRow(Modifier.selectableGroup(), Arrangement.spacedBy(Kit.space.s), Arrangement.spacedBy(Kit.space.s)) {
            KitTag(stringResource(R.string.accent_theme), selected = choice == AccentChoice.Theme, onClick = if (state.editable) ({ ctx.actions.reset(setting, ctx.language) }) else null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                KitTag(stringResource(R.string.accent_wallpaper), selected = choice == AccentChoice.Wallpaper, onClick = if (state.editable) ({ ctx.actions.set(setting, AccentChoice.WALLPAPER, ctx.language) }) else null)
            }
        }
        FlowRow(Modifier.padding(top = Kit.space.s).selectableGroup(), Arrangement.spacedBy(Kit.space.s), Arrangement.spacedBy(Kit.space.s)) {
            AccentInput.SWATCHES.forEach { swatch ->
                val rgb = swatch.rgb ?: (ink.toArgb() and RGB_MASK)
                val id = AccentChoice.Custom(rgb).id
                Swatch(Color(rgb or OPAQUE), stringResource(swatch.label), (choice as? AccentChoice.Custom)?.id == id, state.editable) {
                    ctx.actions.set(setting, id, ctx.language)
                }
            }
        }
        HexField(ctx, state.editable, choice)
    }
}

@Composable
private fun Swatch(color: Color, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Kit.radius.s)
    Box(
        Modifier
            .size(SettingsMetrics.swatch)
            .clip(shape)
            .background(color)
            .border(if (selected) Kit.marker else Kit.hairline, if (selected) Kit.colors.focus else Kit.colors.panelBorder, shape)
            .semantics { contentDescription = label }
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    )
}

@Composable
private fun HexField(ctx: SettingsContext, editable: Boolean, choice: AccentChoice) {
    val setting = AppearanceSettingsSchema.accent
    val colors = Kit.colors
    var text by remember { mutableStateOf((choice as? AccentChoice.Custom)?.id.orEmpty()) }
    // A swatch, reset or another layer changes the stored colour; typing that produced it must not be rewritten mid-keystroke.
    LaunchedEffect(choice) { if (AccentChoice.parse(text) != choice) text = (choice as? AccentChoice.Custom)?.id.orEmpty() }
    val signals = listOf(colors.success, colors.warning, colors.error, colors.info, colors.git.modified, colors.git.conflict)
    val report = AccentInput.report(text, colors.panel, colors.background, signals)
    val unparsed = text.isNotBlank() && report.choice == null
    KitField(
        value = text,
        onValueChange = { next ->
            text = next
            AccentChoice.parse(next)?.takeIf { it is AccentChoice.Custom }?.let { ctx.actions.set(setting, it.id, ctx.language) }
        },
        label = stringResource(R.string.accent_hex_label),
        hint = stringResource(R.string.accent_hex_hint),
        error = if (unparsed) stringResource(R.string.accent_hex_invalid) else null,
        mono = true,
        enabled = editable,
        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        modifier = Modifier.padding(top = Kit.space.m),
    )
    if (report.adjusted && report.shown != null) {
        Row(Modifier.padding(top = Kit.space.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.s)) {
            Swatch(report.shown, stringResource(R.string.accent_adjusted_swatch), selected = false, enabled = false) {}
            BodyText(stringResource(R.string.accent_adjusted), tone = Tone.Warning)
        }
    }
    if (report.nearSignal) BodyText(stringResource(R.string.accent_near_signal), Modifier.padding(top = Kit.space.xs), Tone.Warning)
}

private const val OPAQUE = 0xFF000000.toInt()
private const val RGB_MASK = 0xFFFFFF
