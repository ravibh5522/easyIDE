package dev.easyide.app.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.extensions.adapters.KeyRowChoice
import dev.easyide.extensions.contrib.Owner

/**
 * `keyRows.active` as a picker (customization.md sec 10): Automatic, then every row it can name -
 * the user's layouts, contributed rows and the built-in terminal row - in the order `auto` tries
 * them. Skipped `keyRows.layouts` rows are listed under it.
 */
@Composable
fun KeyRowPickerRow(state: KeyRowPickerState, ctx: SettingsContext) {
    val setting = SettingsSchema.keyRowsActive
    val current = ctx.snapshot.get(setting, ctx.language)
    val auto = stringResource(R.string.key_row_auto)
    val autoLabel = "$auto - ${stringResource(R.string.key_row_auto_detail)}"
    val labels = listOf(autoLabel) + state.choices.map { "${it.title} - ${choiceDetail(it)}" }
    val selected = if (current == SettingsSchema.KEY_ROWS_AUTO) 0 else state.choices.indexOfFirst { it.id == current } + 1
    val missing = if (current != SettingsSchema.KEY_ROWS_AUTO && selected == 0) stringResource(R.string.key_row_missing, current) else null
    PickerRow(
        id = setting.key,
        title = setting.title.resolve(),
        subtitle = setting.description.resolve(),
        labels = labels,
        selected = selected,
        modified = ctx.snapshot.isSetIn(setting, ctx.layer, ctx.language),
        onReset = { ctx.actions.reset(setting, ctx.language) },
        onPick = { i -> ctx.actions.set(setting, if (i == 0) SettingsSchema.KEY_ROWS_AUTO else state.choices[i - 1].id, ctx.language) },
        notes = {
            missing?.let { RowNote(it, Tone.Warning) }
            state.problems.forEach { p -> RowNote(stringResource(R.string.key_row_layout_problem, p.index + 1, p.message), Tone.Danger) }
        },
    )
}

@Composable
private fun choiceDetail(c: KeyRowChoice): String {
    val source = when (val o = c.owner) {
        null -> stringResource(R.string.key_row_user)
        Owner.BuiltIn -> stringResource(R.string.key_row_builtin)
        is Owner.Ext -> o.id.value
    }
    val parts = listOfNotNull(c.id, source, if (c.hidden) stringResource(R.string.key_row_hidden) else null)
    return parts.joinToString(", ")
}
