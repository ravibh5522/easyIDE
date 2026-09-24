package dev.easyide.app.ui.props

import dev.easyide.app.R
import dev.easyide.app.data.settings.Merge
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.data.settings.SettingCategory
import dev.easyide.app.data.settings.SettingScope
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.ui.shell.ContainerPrefs
import dev.easyide.app.ui.shell.LayoutPresets
import dev.easyide.app.ui.shell.NavPrefs
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.nav.NavLabels
import dev.easyide.app.ui.shell.nav.NavPosition
import dev.easyide.app.ui.shell.nav.NavSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The `shell.*` keys of docs/ui-redesign/properties.md 2 and extension-ui.md 6 that the Layout
 * page edits and the shell reads ([navSettings], [navPrefs], [containerPrefs]). One object, registered
 * once through [dev.easyide.app.data.settings.SettingsSchema.all]. Choices are stored by explicit
 * string id, never by enum name. A project layer may set only the layout preset.
 */
object ShellSettingsSchema {

    private val C = SettingCategory.LAYOUT

    /** `auto`, a built-in preset id, or an extension preset id; unknown ids fall back to `auto` in the shell. */
    val layoutPreset = Setting.Str(
        "shell.layout.preset", C, R.string.setting_layout_preset_title, R.string.setting_layout_preset_desc,
        default = LayoutPresets.AUTO, scope = SettingScope.P, pattern = PRESET_ID,
    )

    val navigationPosition = Setting.Enum(
        "shell.navigation.position", C, R.string.setting_nav_position_title, R.string.setting_nav_position_desc,
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
        "shell.navigation.labels", C, R.string.setting_nav_labels_title, R.string.setting_nav_labels_desc,
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

    /** Ids in display order; ids not listed follow by default order. Arrays replace across layers. */
    val navigationOrder = Setting.StrList(
        "shell.navigation.order", C, R.string.setting_nav_order_title, R.string.setting_nav_order_desc,
        default = emptyList(), scope = SettingScope.G,
    )

    val navigationHidden = Setting.StrList(
        "shell.navigation.hidden", C, R.string.setting_nav_hidden_title, R.string.setting_nav_hidden_desc,
        default = emptyList(), scope = SettingScope.G,
    )

    /** Ids that must stay among the visible cells of a compact bottom bar. */
    val navigationPinned = Setting.StrList(
        "shell.navigation.pinned", C, R.string.setting_nav_pinned_title, R.string.setting_nav_pinned_desc,
        default = emptyList(), scope = SettingScope.G,
    )

    /** `{containerId: "sidebar" | "secondarySidebar" | "panel"}`; objects merge key-wise across layers. */
    val containerPlacement = Setting.Json(
        "shell.containers.placement", C, R.string.setting_container_placement_title, R.string.setting_container_placement_desc,
        default = JsonObject(emptyMap()), scope = SettingScope.G, accepts = ::isPlacementMap, merge = Merge.OBJECT,
    )

    val containersHidden = Setting.StrList(
        "shell.containers.hidden", C, R.string.setting_container_hidden_title, R.string.setting_container_hidden_desc,
        default = emptyList(), scope = SettingScope.G,
    )

    val all: List<Setting<*>> = listOf(
        layoutPreset, navigationPosition, navigationLabels, navigationOrder, navigationHidden, navigationPinned,
        containerPlacement, containersHidden,
    )

    fun navSettings(settings: SettingsSnapshot): NavSettings =
        NavSettings(navPrefs(settings), settings[navigationPosition], settings[navigationLabels])

    fun navPrefs(settings: SettingsSnapshot): NavPrefs = NavPrefs(
        order = settings[navigationOrder],
        hidden = settings[navigationHidden].toSet(),
        pinned = settings[navigationPinned].toSet(),
    )

    fun containerPrefs(settings: SettingsSnapshot): ContainerPrefs = ContainerPrefs(
        placement = placementMap(settings[containerPlacement]),
        hidden = settings[containersHidden].toSet(),
    )

    /** The entries of a stored placement map that name a known placement; the rest are ignored. */
    fun placementMap(json: JsonElement): Map<String, Placement> = buildMap {
        (json as? JsonObject)?.forEach { (id, value) -> (value as? JsonPrimitive)?.contentOrNull?.let(Placement::ofWire)?.let { put(id, it) } }
    }

    private fun isPlacementMap(value: JsonElement): Boolean =
        value is JsonObject && value.values.all { v -> v is JsonPrimitive && v.isString && Placement.ofWire(v.content) != null }
}

private val PRESET_ID = Regex("""[A-Za-z][A-Za-z0-9._-]{0,63}""")
