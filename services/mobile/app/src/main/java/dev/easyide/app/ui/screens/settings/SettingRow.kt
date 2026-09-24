package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicText
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
import dev.easyide.app.ui.kit.ChoiceLayout
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitChoice
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.kit.Twistie
import dev.easyide.app.ui.kit.TwistieSlot
import dev.easyide.app.ui.kit.choiceLayout

/**
 * One schema-driven row: the effective value for the selected layer (and language), a control
 * chosen by the setting's type, the modified dot and reset while this layer holds a value, a layer
 * badge when another layer decides, and "overridden by" when a higher one wins (CUS-02). Rows the
 * layer cannot hold are shown read-only with the reason. [contextLabel] leads the description in
 * search results, where the page is not otherwise visible. [inlineChoice] keeps a choice's options
 * visible: beside the label as segments on a wide page, under the row otherwise.
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
    val segments = choice != null && inlineChoice && state.editable && !LocalRowLayout.current.narrow && choiceLayout(choice.labels) == ChoiceLayout.Segmented
    val requester = remember { BringIntoViewRequester() }
    val marked = ctx.highlight == setting.key
    LaunchedEffect(marked) { if (marked) requester.bringIntoView() }

    val description = setting.description.resolve()
    val on = switchOn(setting, value)?.let { stringResource(if (it) R.string.level_on else R.string.level_off) }
    val reset = { ctx.actions.reset(setting, ctx.language) }
    val optionsBelow = choice != null && state.editable && !segments && (inlineChoice || open)
    Column(modifier.bringIntoViewRequester(requester)) {
        SettingLine(
            title = setting.title.resolve(),
            description = contextLabel?.let { "$it > $description" } ?: description,
            id = "setting:${setting.key}",
            modified = state.modifiedHere,
            modifier = if (on != null) Modifier.semantics { stateDescription = on } else Modifier,
            wide = isWideControl(setting),
            marked = marked,
            enabled = state.editable,
            onClick = if (expandable) ({ open = !open }) else rowClick(setting, value, state.editable, ctx),
        ) {
            val shown = state.modifiedHere && state.editable
            if (isWideControl(setting)) {
                WideWithReset(shown, reset) { WideControl(setting, value, state.editable, ctx.language, ctx.actions) }
            } else {
                WithReset(shown, reset) {
                    if (segments && choice != null) KitChoice(choice.labels.indices.toList(), choice.selected, { choice.labels[it] }, choice.pick)
                    // Options that stay open under the row already show the value.
                    else if (!(inlineChoice && optionsBelow)) CompactControl(setting, value, state.editable, ctx, choice)
                    if (expandable && state.editable) TwistieSlot(if (open) Twistie.Expanded else Twistie.Collapsed, Kit.colors.textMuted)
                }
            }
        }
        RowNotes(setting, state, ctx.layer)
        if (optionsBelow && choice != null) RowBlock { ChoiceBlock(choice) }
    }
}

@Composable
private fun RowNotes(setting: Setting<*>, state: RowState, layer: LayerId) {
    state.winnerLayer?.takeIf { it != layer }?.let { LayerNote(it, state.overriddenBy) }
    if (state.invalidHere) RowNote(stringResource(R.string.setting_invalid_here), Tone.Danger)
    state.block?.let { RowNote(stringResource(blockText(it, layer))) }
    if (state.protectedKey && state.editable) RowNote(stringResource(R.string.setting_protected_note))
    setting.deprecation?.let { RowNote(it, Tone.Danger) }
}

/** The layer badge and, when that layer sits above the selected one, why the row's own value does not apply. */
@Composable
private fun LayerNote(winner: LayerId, overriddenBy: LayerId?) {
    Row(
        Modifier.padding(horizontal = Kit.control.hPad).padding(bottom = Kit.space.s),
        Arrangement.spacedBy(Kit.space.s), Alignment.CenterVertically,
    ) {
        KitTag(stringResource(layerLabel(winner)), tone = if (overriddenBy != null) Tone.Warning else Tone.Info)
        if (overriddenBy != null) {
            BasicText(
                stringResource(R.string.setting_overridden_note, stringResource(layerLabel(overriddenBy))),
                style = Kit.text.caption.copy(color = Tone.Warning.content(Kit.colors)),
            )
        }
    }
}

private fun blockText(block: RowBlock, layer: LayerId): Int = when (block) {
    RowBlock.LAYER_SCOPE -> R.string.setting_not_in_layer
    RowBlock.PROTECTED_IN_LAYER -> if (layer == LayerId.PROJECT) R.string.setting_protected_in_project else R.string.setting_not_in_layer
    RowBlock.NOT_PER_LANGUAGE -> R.string.setting_not_per_language
}
