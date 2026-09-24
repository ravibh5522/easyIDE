package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.data.settings.AppearanceSettingsSchema
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.Tone

/**
 * The Appearance page (properties.md 7): a live preview on top, then the theme, the accent, the
 * shape and density of the interface, motion and feedback, and the customization keys that have no
 * control of their own. Every row is a `appearance.*` property, so a search finds it by name. The
 * choices with a few short values are always open (segmented); "Reset appearance" clears this layer's values.
 */
@Composable
internal fun AppearancePage(env: PageEnv) {
    val ctx = env.ctx
    var confirming by rememberSaveable { mutableStateOf(false) }
    AppearancePreview()
    KitSection(stringResource(R.string.appearance_theme_section)) {
        SettingRowFor(SettingsSchema.themeMode, env)
    }
    KitSection(stringResource(R.string.appearance_accent_section)) { AccentRow(ctx) }
    KitSection(stringResource(R.string.appearance_shape_section)) { InlineRows(ctx, SHAPE_ROWS) }
    KitSection(stringResource(R.string.appearance_motion_section)) { InlineRows(ctx, MOTION_ROWS) }
    KitSection(stringResource(R.string.appearance_interface_section)) { InlineRows(ctx, INTERFACE_ROWS) }
    CategoryRows(pageSettings(SettingsCategory.APPEARANCE, env.settings).filter { it !in HANDLED }, env)
    Row(Modifier.padding(start = Kit.space.l, end = Kit.space.l, top = Kit.space.l)) {
        KitButton(stringResource(R.string.appearance_reset), { confirming = true }, style = KitButtonStyle.Secondary)
    }
    if (confirming) {
        val layer = stringResource(layerLabel(ctx.layer))
        KitDialog(
            title = stringResource(R.string.appearance_reset_question, layer),
            onDismiss = { confirming = false },
            confirm = KitAction(stringResource(R.string.settings_reset_all_confirm)) {
                ctx.actions.resetMany(APPEARANCE_KEYS.filter { ctx.snapshot.isSetIn(it, ctx.layer) })
                confirming = false
            },
            dismiss = KitAction(stringResource(R.string.action_cancel)) { confirming = false },
            tone = Tone.Danger,
        ) { BodyText(stringResource(R.string.appearance_reset_body, layer)) }
    }
}

@Composable
private fun InlineRows(ctx: SettingsContext, rows: List<Setting<*>>) {
    rows.forEach { SettingRow(it, ctx, inlineChoice = true) }
}

private val SHAPE_ROWS: List<Setting<*>> = with(AppearanceSettingsSchema) { listOf(density, corners, uiScale, fontPairing, chromeContrast) }
private val MOTION_ROWS: List<Setting<*>> = with(AppearanceSettingsSchema) { listOf(motif, cursorBlink, reduceMotion, haptics) }
private val INTERFACE_ROWS: List<Setting<*>> = with(AppearanceSettingsSchema) { listOf(iconStyle, handedness) }

/** Every `appearance.*` property plus the theme keys the picker writes. */
private val APPEARANCE_KEYS: List<Setting<*>> = AppearanceSettingsSchema.all + SettingsSchema.themeMode + SettingsSchema.colorTheme

/** Rows this page draws itself, so the generic list below them does not repeat them. */
private val HANDLED: Set<Setting<*>> = (AppearanceSettingsSchema.all + SettingsSchema.themeMode).toSet()
