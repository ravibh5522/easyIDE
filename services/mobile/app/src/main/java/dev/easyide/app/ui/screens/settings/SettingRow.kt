package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.easyide.app.R
import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone

/**
 * One schema-driven row: the effective value for the selected layer (and language), a control
 * chosen by the setting's type, the modified dot and reset while this layer holds a value, a layer
 * badge when another layer decides, and "overridden by" when a higher one wins (CUS-02). Rows the
 * layer cannot hold are shown read-only with the reason. [contextLabel] leads the description in
 * search results, where the page is not otherwise visible. [inlineChoice] keeps a choice's options
 * open under the row instead of behind a tap.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingRow(
    setting: Setting<*>,
    ctx: SettingsContext,
    modifier: Modifier = Modifier,
    contextLabel: String? = null,
    inlineChoice: Boolean = false,
) {
    val state = RowState.of(setting, ctx.snapshot, ctx.layer, ctx.language)
    val value = ctx.snapshot.inspect(setting, ctx.language).value
    var open by rememberSaveable(setting.key) { mutableStateOf(false) }
    val choice = choiceOf(setting, value, ctx.language, ctx.actions)
    val expandable = choice != null && !inlineChoice
    val requester = remember { BringIntoViewRequester() }
    val marked = ctx.highlight == setting.key
    LaunchedEffect(marked) { if (marked) requester.bringIntoView() }

    val description = setting.description.resolve()
    val on = switchOn(setting, value)?.let { stringResource(if (it) R.string.level_on else R.string.level_off) }
    Column(modifier.bringIntoViewRequester(requester)) {
        KitRow(
            modifier = if (on != null) Modifier.semantics { stateDescription = on } else Modifier,
            title = setting.title.resolve(),
            subtitle = contextLabel?.let { "$it - $description" } ?: description,
            leading = { ModifiedDot(state.modifiedHere) },
            trailing = { RowTrailing(setting, ctx, state, value, choice) },
            onClick = when {
                expandable -> ({ open = !open })
                else -> rowClick(setting, value, state.editable, ctx)
            },
            selected = marked,
            enabled = state.editable,
            id = "setting:${setting.key}",
        )
        RowNotes(setting, state, ctx.layer)
        if (choice != null && state.editable && (inlineChoice || open)) RowBlock { ChoiceBlock(choice) }
        WideControl(setting, value, state.editable, ctx.language, ctx.actions)
    }
}

@Composable
private fun RowTrailing(setting: Setting<*>, ctx: SettingsContext, state: RowState, value: Any?, choice: Choice?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
        state.winnerLayer?.takeIf { it != ctx.layer }?.let { layer ->
            KitTag(stringResource(layerLabel(layer)), tone = if (state.overriddenBy != null) Tone.Warning else Tone.Info)
        }
        if (state.modifiedHere && state.editable) {
            KitIconButton(Icons.Filled.Restore, stringResource(R.string.setting_reset), { ctx.actions.reset(setting, ctx.language) })
        }
        CompactControl(setting, value, state.editable, ctx, choice)
    }
}

@Composable
private fun RowNotes(setting: Setting<*>, state: RowState, layer: LayerId) {
    state.overriddenBy?.let { RowNote(stringResource(R.string.setting_overridden_note, stringResource(layerLabel(it))), Tone.Warning) }
    if (state.invalidHere) RowNote(stringResource(R.string.setting_invalid_here), Tone.Danger)
    state.block?.let { RowNote(stringResource(blockText(it, layer))) }
    if (state.protectedKey && state.editable) RowNote(stringResource(R.string.setting_protected_note))
    setting.deprecation?.let { RowNote(it, Tone.Danger) }
}

private fun blockText(block: RowBlock, layer: LayerId): Int = when (block) {
    RowBlock.LAYER_SCOPE -> R.string.setting_not_in_layer
    RowBlock.PROTECTED_IN_LAYER -> if (layer == LayerId.PROJECT) R.string.setting_protected_in_project else R.string.setting_not_in_layer
    RowBlock.NOT_PER_LANGUAGE -> R.string.setting_not_per_language
}
