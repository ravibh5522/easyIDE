package dev.easyide.app.ui.screens.settings

import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.SchemaState
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.layer
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.app.ui.commands.KeybindingList
import dev.easyide.app.ui.commands.KeybindingsFile
import dev.easyide.app.ui.commands.Keymap
import dev.easyide.app.ui.theme.ThemeMode
import kotlinx.serialization.json.JsonElement

/** Inert writes: a golden only looks at the rows, so nothing here stores anything. */
private object NoActions : SettingActions, ThemeActions {
    override fun <T> set(setting: Setting<T>, value: T, language: String?) = Unit
    override fun setJson(setting: Setting<*>, value: JsonElement, language: String?) = Unit
    override fun reset(setting: Setting<*>, language: String?) = Unit
    override fun resetMany(settings: List<Setting<*>>) = Unit
    override fun selectBuiltInTheme(mode: ThemeMode) = Unit
    override fun selectContributedTheme(label: String) = Unit
    override fun resetTheme() = Unit
}

/** Two values in the user layer, so the modified dot and the reset button are on screen. */
private val USER_VALUES = """{"editor.fontSize": 16, "terminal.fontSize": 15}"""

/** What a page reads: the built-in schema with the user layer above it, and inert writes. */
internal object SettingsFixtures {
    val snapshot = SettingsSnapshot(SchemaState.builtInOnly(SettingsSchema.all), listOf(layer(LayerId.USER, USER_VALUES)))

    val ui = SettingsUiState(settings = snapshot)

    val ctx = SettingsContext(snapshot, LayerId.USER, null, NoActions, {}, null)

    val env = PageEnv(ctx, SettingsSchema.all, emptyList(), NoActions, KeyRowPickerState())

    val keybindings = KeybindingsScreenState(
        KeybindingList.build(Keymap.DEFAULT.bindings, emptyList(), KeybindingsFile.parse(""), CommandIds.ALL),
        CommandIds.ALL.sorted(),
    )
}
