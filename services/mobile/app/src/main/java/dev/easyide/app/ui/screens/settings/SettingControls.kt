package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.data.settings.ContributedControl
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitChoice
import dev.easyide.app.ui.kit.KitToggle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** The options of an enum or a contributed choice, as labels with the selected index. */
internal class Choice(val labels: List<String>, val details: List<String?>, val selected: Int, val pick: (Int) -> Unit)

@Composable
internal fun choiceOf(setting: Setting<*>, value: Any?, language: String?, actions: SettingActions): Choice? = when (setting) {
    is Setting.Enum<*> -> enumChoice(setting, value, language, actions)
    is Setting.Contributed -> (setting.control as? ContributedControl.Choice)?.let { c ->
        val current = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        Choice(
            labels = c.values,
            details = c.values.indices.map { c.descriptions.getOrNull(it)?.takeIf(String::isNotEmpty) },
            selected = c.values.indexOf(current),
            pick = { actions.setJson(setting, JsonPrimitive(c.values[it]), language) },
        )
    }
    else -> null
}

@Composable
private fun <E : Enum<E>> enumChoice(setting: Setting.Enum<E>, value: Any?, language: String?, actions: SettingActions): Choice {
    @Suppress("UNCHECKED_CAST") // resolved values of a Setting.Enum<E> are E
    val current = value as E
    return Choice(
        labels = setting.values.map { stringResource(setting.label(it)) },
        details = setting.values.map { null },
        selected = setting.values.indexOf(current),
        pick = { actions.set(setting, setting.values[it], language) },
    )
}

@Composable
internal fun ChoiceBlock(choice: Choice) {
    KitChoice(choice.labels.indices.toList(), choice.selected, { choice.labels[it] }, choice.pick)
    choice.details.getOrNull(choice.selected)?.let {
        BasicText(it, style = Kit.type.bodySmall.copy(color = Kit.colors.textMuted))
    }
}

/** On or off for a boolean setting, declared or contributed; null for every other kind. */
internal fun switchOn(setting: Setting<*>, value: Any?): Boolean? = when {
    setting is Setting.Bool -> value as Boolean
    setting is Setting.Contributed && setting.control == ContributedControl.Switch ->
        (value as? JsonPrimitive)?.booleanOrNull == true
    else -> null
}

/** The row's tap: a toggle flips; other kinds have no row action of their own. */
internal fun rowClick(setting: Setting<*>, value: Any?, enabled: Boolean, ctx: SettingsContext): (() -> Unit)? {
    val on = switchOn(setting, value)
    if (on == null || !enabled) return null
    return { writeSwitch(setting, !on, ctx) }
}

private fun writeSwitch(setting: Setting<*>, on: Boolean, ctx: SettingsContext) {
    if (setting is Setting.Bool) ctx.actions.set(setting, on, ctx.language) else ctx.actions.setJson(setting, JsonPrimitive(on), ctx.language)
}

/** Controls that fit beside the title: the switch glyph, a stepper, the chosen value, the JSON action. */
@Composable
internal fun CompactControl(setting: Setting<*>, value: Any?, enabled: Boolean, ctx: SettingsContext, choice: Choice?) {
    switchOn(setting, value)?.let { KitToggle(it, null, enabled = enabled); return }
    if (choice != null) {
        ValueText(choice.labels.getOrNull(choice.selected) ?: stringResource(R.string.setting_no_value))
        return
    }
    when (setting) {
        is Setting.IntRange -> Stepper(value as Int, setting.min, setting.max, setting.step, enabled) { ctx.actions.set(setting, it, ctx.language) }
        is Setting.Contributed -> when (val c = setting.control) {
            is ContributedControl.Stepper -> Stepper(((value as? JsonPrimitive)?.intOrNull ?: c.min), c.min, c.max, 1, enabled) {
                ctx.actions.setJson(setting, JsonPrimitive(it), ctx.language)
            }
            ContributedControl.JsonOnly -> JsonAction(enabled, ctx)
            else -> Unit
        }
        // Objects (lsp.servers, codeActionsOnSave) are edited in settings.json, like JsonOnly.
        is Setting.Json -> JsonAction(enabled, ctx)
        else -> Unit
    }
}

@Composable
private fun JsonAction(enabled: Boolean, ctx: SettingsContext) {
    KitButton(stringResource(R.string.setting_edit_in_json), ctx.onEditJson, style = KitButtonStyle.Ghost, enabled = enabled)
}

/** Controls that need the row's full width: text fields and list editors. */
@Composable
internal fun WideControl(setting: Setting<*>, value: Any?, enabled: Boolean, language: String?, actions: SettingActions) {
    when (setting) {
        is Setting.Str -> RowBlock { SettingTextField(value as String, singleLine = true, enabled = enabled) { actions.set(setting, it, language) } }
        is Setting.StrList -> {
            @Suppress("UNCHECKED_CAST") // Setting.StrList decodes to List<String> by construction
            val lines = value as List<String>
            RowBlock {
                SettingTextField(lines.joinToString("\n"), singleLine = false, enabled = enabled, mono = true) {
                    actions.set(setting, it.lines().filter(String::isNotBlank), language)
                }
            }
        }
        is Setting.Contributed -> ContributedWideControl(setting, value as JsonElement, enabled, language, actions)
        else -> Unit
    }
}

@Composable
private fun ContributedWideControl(setting: Setting.Contributed, value: JsonElement, enabled: Boolean, language: String?, actions: SettingActions) {
    when (setting.control) {
        is ContributedControl.TextField -> RowBlock {
            SettingTextField(
                initial = (value as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                singleLine = true,
                enabled = enabled,
                isValid = { setting.isValid(JsonPrimitive(it)) },
            ) { actions.setJson(setting, JsonPrimitive(it), language) }
        }
        ContributedControl.NumberField -> RowBlock {
            SettingTextField(
                initial = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content.orEmpty(),
                singleLine = true,
                enabled = enabled,
                isValid = { text -> text.toDoubleOrNull()?.let { setting.isValid(numberJson(text)) } == true },
            ) { text -> if (text.toDoubleOrNull() != null) actions.setJson(setting, numberJson(text), language) }
        }
        ContributedControl.StringList -> RowBlock {
            SettingTextField(
                initial = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.joinToString("\n").orEmpty(),
                singleLine = false,
                enabled = enabled,
                mono = true,
            ) { text -> actions.setJson(setting, JsonArray(text.lines().filter(String::isNotBlank).map(::JsonPrimitive)), language) }
        }
        else -> Unit
    }
}

/** An integer written as an integer, so an `integer`-typed schema accepts it. */
private fun numberJson(text: String): JsonPrimitive = text.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(text.toDouble())
