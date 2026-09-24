package dev.easyide.app.data.settings

import dev.easyide.app.R
import dev.easyide.app.ui.props.AccentChoice
import dev.easyide.app.ui.props.Appearance
import dev.easyide.app.ui.props.ChromeContrast
import dev.easyide.app.ui.props.Corners
import dev.easyide.app.ui.props.DensityPref
import dev.easyide.app.ui.props.FontPairing
import dev.easyide.app.ui.props.Handedness
import dev.easyide.app.ui.props.HapticsLevel
import dev.easyide.app.ui.props.IconStyle
import dev.easyide.app.ui.props.Motif
import dev.easyide.app.ui.props.ReduceMotion

/**
 * The `appearance.*` design properties (docs/ui-redesign/properties.md 2). All user-level except
 * the accent, which a project may override. A separate file so the several branches that edit
 * [SettingsSchema] do not conflict with it. Choices are stored by their explicit string ids.
 * The scale is a percent because the schema has no fractional type.
 */
object AppearanceSettingsSchema {

    private val C = SettingCategory.APPEARANCE

    val accent = Setting.Str(
        "appearance.accent", C, R.string.setting_accent_title, R.string.setting_accent_desc,
        default = AccentChoice.THEME, scope = SettingScope.P, pattern = ACCENT_PATTERN,
    )

    /** `auto` follows the width class (dense on a tablet, comfortable on a phone); `compact` is the old spelling of `dense`. */
    val density = Setting.Enum(
        "appearance.density", C, R.string.setting_density_title, R.string.setting_density_desc,
        DensityPref.AUTO, SettingScope.G, DensityPref.entries,
        { when (it) { DensityPref.AUTO -> R.string.density_auto; DensityPref.DENSE -> R.string.density_dense; DensityPref.COMFORTABLE -> R.string.density_comfortable; DensityPref.SPACIOUS -> R.string.density_spacious } },
        aliases = { e -> SchemaValidator.stringOrNull(e)?.let(DensityPref.entriesById::get) },
        id = DensityPref::id,
    )

    val corners = Setting.Enum(
        "appearance.corners", C, R.string.setting_corners_title, R.string.setting_corners_desc,
        Corners.SOFT, SettingScope.G, Corners.entries,
        { when (it) { Corners.SHARP -> R.string.corners_sharp; Corners.SOFT -> R.string.corners_soft; Corners.ROUND -> R.string.corners_round } },
        id = Corners::id,
    )

    val uiScale = Setting.IntRange(
        "appearance.uiScale", C, R.string.setting_ui_scale_title, R.string.setting_ui_scale_desc,
        default = Appearance.UI_SCALE_DEFAULT, scope = SettingScope.G,
        min = Appearance.UI_SCALE_MIN, max = Appearance.UI_SCALE_MAX, step = Appearance.UI_SCALE_STEP,
    )

    val fontPairing = Setting.Enum(
        "appearance.fontPairing", C, R.string.setting_font_pairing_title, R.string.setting_font_pairing_desc,
        FontPairing.GEIST, SettingScope.G, FontPairing.entries,
        { when (it) { FontPairing.GEIST -> R.string.font_pairing_geist; FontPairing.MONO_CHROME -> R.string.font_pairing_mono; FontPairing.SYSTEM -> R.string.font_pairing_system } },
        id = FontPairing::id,
    )

    val chromeContrast = Setting.Enum(
        "appearance.chromeContrast", C, R.string.setting_chrome_contrast_title, R.string.setting_chrome_contrast_desc,
        ChromeContrast.NORMAL, SettingScope.G, ChromeContrast.entries,
        { when (it) { ChromeContrast.SOFT -> R.string.contrast_soft; ChromeContrast.NORMAL -> R.string.contrast_normal; ChromeContrast.HIGH -> R.string.contrast_high } },
        id = ChromeContrast::id,
    )

    val motif = Setting.Enum(
        "appearance.motif", C, R.string.setting_motif_title, R.string.setting_motif_desc,
        Motif.SUBTLE, SettingScope.G, Motif.entries,
        { when (it) { Motif.OFF -> R.string.level_off; Motif.SUBTLE -> R.string.level_subtle; Motif.FULL -> R.string.level_full } },
        id = Motif::id,
    )

    val cursorBlink = Setting.Bool(
        "appearance.cursorBlink", C, R.string.setting_cursor_blink_title, R.string.setting_cursor_blink_desc,
        default = true, scope = SettingScope.G,
    )

    val reduceMotion = Setting.Enum(
        "appearance.reduceMotion", C, R.string.setting_reduce_motion_title, R.string.setting_reduce_motion_desc,
        ReduceMotion.SYSTEM, SettingScope.G, ReduceMotion.entries,
        { when (it) { ReduceMotion.SYSTEM -> R.string.reduce_motion_system; ReduceMotion.ON -> R.string.level_on; ReduceMotion.OFF -> R.string.level_off } },
        id = ReduceMotion::id,
    )

    val haptics = Setting.Enum(
        "appearance.haptics", C, R.string.setting_haptics_title, R.string.setting_haptics_desc,
        HapticsLevel.SUBTLE, SettingScope.G, HapticsLevel.entries,
        { when (it) { HapticsLevel.OFF -> R.string.level_off; HapticsLevel.SUBTLE -> R.string.level_subtle; HapticsLevel.FULL -> R.string.level_full } },
        id = HapticsLevel::id,
    )

    val iconStyle = Setting.Enum(
        "appearance.iconStyle", C, R.string.setting_icon_style_title, R.string.setting_icon_style_desc,
        IconStyle.EI, SettingScope.G, IconStyle.entries,
        { when (it) { IconStyle.EI -> R.string.icon_style_ei; IconStyle.MATERIAL -> R.string.icon_style_material } },
        id = IconStyle::id,
    )

    val handedness = Setting.Enum(
        "appearance.handedness", C, R.string.setting_handedness_title, R.string.setting_handedness_desc,
        Handedness.RIGHT, SettingScope.G, Handedness.entries,
        { when (it) { Handedness.RIGHT -> R.string.handedness_right; Handedness.LEFT -> R.string.handedness_left } },
        id = Handedness::id,
    )

    val all: List<Setting<*>> = listOf(
        accent, density, corners, uiScale, fontPairing, chromeContrast, motif, cursorBlink,
        reduceMotion, haptics, iconStyle, handedness,
    )

    /** The resolved properties. Invalid stored values were already skipped by resolution, so this never fails. */
    fun appearance(settings: SettingsSnapshot): Appearance = Appearance(
        accent = AccentChoice.parse(settings[accent]) ?: AccentChoice.Theme,
        density = settings[density].density,
        corners = settings[corners],
        uiScalePercent = settings[uiScale],
        fontPairing = settings[fontPairing],
        chromeContrast = settings[chromeContrast],
        motif = settings[motif],
        cursorBlink = settings[cursorBlink],
        reduceMotion = settings[reduceMotion],
        haptics = settings[haptics],
        iconStyle = settings[iconStyle],
        handedness = settings[handedness],
    )
}

/** `theme`, `wallpaper` or a `#` hex colour of 3, 4, 6 or 8 digits; the value is parsed by [AccentChoice.parse]. */
private val ACCENT_PATTERN = Regex("""theme|wallpaper|#([0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})""")
