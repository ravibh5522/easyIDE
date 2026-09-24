package dev.easyide.app.data.settings

import dev.easyide.app.R
import dev.easyide.app.ui.shell.LayoutPresets
import dev.easyide.app.ui.shell.NavPrefs
import dev.easyide.app.ui.shell.nav.NavLabels
import dev.easyide.app.ui.shell.nav.NavPosition
import dev.easyide.app.ui.shell.nav.NavSettings

/**
 * The `shell.*` properties (docs/ui-redesign/properties.md 2 and extension-ui.md 6): where the
 * navigation surface sits, what it shows and in which order, and the layout preset. A separate
 * file, like [AppearanceSettingsSchema], so the branches editing [SettingsSchema] do not conflict.
 * Listed under the Appearance category until Settings gets its Layout page.
 */
object ShellSettingsSchema {

    private val C = SettingCategory.APPEARANCE

    val navigationOrder = Setting.StrList(
        "shell.navigation.order", C, R.string.setting_shell_nav_order_title, R.string.setting_shell_nav_order_desc,
        default = emptyList(), scope = SettingScope.G,
    )

    val navigationHidden = Setting.StrList(
        "shell.navigation.hidden", C, R.string.setting_shell_nav_hidden_title, R.string.setting_shell_nav_hidden_desc,
        default = emptyList(), scope = SettingScope.G,
    )

    val navigationPinned = Setting.StrList(
        "shell.navigation.pinned", C, R.string.setting_shell_nav_pinned_title, R.string.setting_shell_nav_pinned_desc,
        default = emptyList(), scope = SettingScope.G,
    )

    val navigationPosition = Setting.Enum(
        "shell.navigation.position", C, R.string.setting_shell_nav_position_title, R.string.setting_shell_nav_position_desc,
        NavPosition.AUTO, SettingScope.G, NavPosition.entries,
        {
            when (it) {
                NavPosition.AUTO -> R.string.nav_position_auto
                NavPosition.LEFT -> R.string.nav_position_left
                NavPosition.RIGHT -> R.string.nav_position_right
                NavPosition.BOTTOM -> R.string.nav_position_bottom
            }
        },
        id = NavPosition::id,
    )

    val navigationLabels = Setting.Enum(
        "shell.navigation.labels", C, R.string.setting_shell_nav_labels_title, R.string.setting_shell_nav_labels_desc,
        NavLabels.AUTO, SettingScope.G, NavLabels.entries,
        {
            when (it) {
                NavLabels.AUTO -> R.string.nav_labels_auto
                NavLabels.ALWAYS -> R.string.nav_labels_always
                NavLabels.NEVER -> R.string.nav_labels_never
            }
        },
        id = NavLabels::id,
    )

    /** A built-in preset id or an extension's `<extension id>.<name>`; whether it exists is decided where it is applied. */
    val layoutPreset = Setting.Str(
        "shell.layout.preset", C, R.string.setting_shell_layout_preset_title, R.string.setting_shell_layout_preset_desc,
        default = LayoutPresets.AUTO, scope = SettingScope.P, pattern = PRESET_PATTERN,
    )

    val all: List<Setting<*>> = listOf(
        navigationOrder, navigationHidden, navigationPinned, navigationPosition, navigationLabels, layoutPreset,
    )

    fun navSettings(settings: SettingsSnapshot): NavSettings = NavSettings(
        prefs = NavPrefs(
            order = settings[navigationOrder],
            hidden = settings[navigationHidden].toSet(),
            pinned = settings[navigationPinned].toSet(),
        ),
        position = settings[navigationPosition],
        labels = settings[navigationLabels],
    )
}

private val PRESET_PATTERN = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")
