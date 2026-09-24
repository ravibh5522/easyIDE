package dev.easyide.app.ui.screens.settings

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.Text as SettingText
import kotlinx.serialization.json.JsonElement

/**
 * Writes from a settings row into the selected layer. Generic methods, so one object serves every
 * setting type; [language] targets a `[lang]` block.
 */
interface SettingActions {
    fun <T> set(setting: Setting<T>, value: T, language: String?)
    fun setJson(setting: Setting<*>, value: JsonElement, language: String?)
    fun reset(setting: Setting<*>, language: String?)

    /** One write for all of [settings], so a bulk reset is one change to observers. */
    fun resetMany(settings: List<Setting<*>>)
}

/** Resolves a setting's display text outside composition (search needs it for every row). */
fun SettingText.resolve(resources: Resources): String = when (this) {
    is SettingText.Res -> resources.getString(id)
    is SettingText.Literal -> text
}

@Composable
fun SettingText.resolve(): String = when (this) {
    is SettingText.Res -> stringResource(id)
    is SettingText.Literal -> text
}

/**
 * What every row of a page reads and writes: the resolved settings for the selected layer, the
 * language override, the write target, and the key a search result asked to be marked.
 */
class SettingsContext(
    val snapshot: SettingsSnapshot,
    val layer: LayerId,
    val language: String?,
    val actions: SettingActions,
    val onEditJson: () -> Unit,
    val highlight: String?,
)
