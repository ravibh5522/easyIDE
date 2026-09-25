package dev.easyide.app.ui.kit.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitChoice
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitStepper
import dev.easyide.app.ui.kit.KitTabs
import dev.easyide.app.ui.kit.KitToggle
import dev.easyide.app.ui.kit.TabStyle
import dev.easyide.app.ui.kit.ToggleKind

private val KINDS = listOf(
    ToggleKind.Switch to R.string.gallery_kind_switch,
    ToggleKind.Check to R.string.gallery_kind_check,
    ToggleKind.Radio to R.string.gallery_kind_radio,
)

/** KitField empty, filled, labelled, errored, disabled, read-only, mono, multi-line and long. */
@Composable
fun FieldsSection() {
    var plain by remember { mutableStateOf("") }
    var filled by remember { mutableStateOf("") }
    var mono by remember { mutableStateOf("") }
    var multi by remember { mutableStateOf("") }
    val hint = stringResource(R.string.gallery_field_hint)
    KitSection(stringResource(R.string.gallery_fields_title)) {
        Sample(stringResource(R.string.gallery_field_empty)) { KitField(plain, { plain = it }, hint = hint) }
        Sample(stringResource(R.string.gallery_field_label)) {
            KitField(filled.ifEmpty { stringResource(R.string.gallery_field_value) }, { filled = it }, label = stringResource(R.string.gallery_field_label_text), hint = hint)
        }
        Sample(stringResource(R.string.gallery_state_error)) {
            KitField(stringResource(R.string.gallery_field_value), {}, label = stringResource(R.string.gallery_field_label_text), error = stringResource(R.string.gallery_field_error))
        }
        Sample(stringResource(R.string.gallery_state_disabled)) {
            KitField(stringResource(R.string.gallery_field_value), {}, label = stringResource(R.string.gallery_field_label_text), enabled = false)
        }
        Sample(stringResource(R.string.gallery_field_readonly)) {
            KitField(stringResource(R.string.gallery_field_value), {}, label = stringResource(R.string.gallery_field_label_text), readOnly = true)
        }
        Sample(stringResource(R.string.gallery_field_mono)) {
            KitField(mono.ifEmpty { stringResource(R.string.gallery_sample_path) }, { mono = it }, mono = true)
        }
        Sample(stringResource(R.string.gallery_field_multiline)) {
            KitField(multi.ifEmpty { stringResource(R.string.gallery_long_body) }, { multi = it }, singleLine = false, label = stringResource(R.string.gallery_field_label_text))
        }
        Sample(stringResource(R.string.gallery_long_text)) {
            KitField(stringResource(R.string.gallery_long_label), {}, label = stringResource(R.string.gallery_long_label), error = stringResource(R.string.gallery_long_body))
        }
    }
}

/** KitToggle in each kind: off, on, disabled both ways, and the display-only form (no handler) a row owns. */
@Composable
fun TogglesSection() {
    var states by remember { mutableStateOf(List(KINDS.size) { false }) }
    KitSection(stringResource(R.string.gallery_toggles_title), description = stringResource(R.string.gallery_toggles_note)) {
        KINDS.forEachIndexed { i, (kind, label) ->
            Sample(stringResource(label)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Kit.space.m), verticalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
                    KitToggle(states[i], { on -> states = states.toMutableList().also { it[i] = on } }, kind = kind)
                    KitToggle(false, {}, kind = kind, enabled = false)
                    KitToggle(true, {}, kind = kind, enabled = false)
                    KitToggle(true, null, kind = kind)
                }
            }
        }
    }
}

/** KitTabs in both styles (few, many and long labels), KitChoice in both layouts, and KitStepper. */
@Composable
fun NavigationSection() {
    var underline by remember { mutableStateOf(0) }
    var segmented by remember { mutableStateOf(1) }
    var many by remember { mutableStateOf(0) }
    var choice by remember { mutableStateOf(0) }
    var list by remember { mutableStateOf(0) }
    val few = listOf(R.string.gallery_tab_files, R.string.gallery_tab_search, R.string.gallery_tab_git).map { stringResource(it) }
    val long = listOf(R.string.gallery_long_label, R.string.gallery_tab_search, R.string.gallery_long_label, R.string.gallery_tab_git).map { stringResource(it) }
    val options = (0 until CHOICES).toList()
    val names = listOf(R.string.gallery_tab_files, R.string.gallery_tab_search, R.string.gallery_tab_git).map { stringResource(it) }
    val wide = listOf(R.string.gallery_long_label, R.string.gallery_field_label_text, R.string.gallery_tag_text).map { stringResource(it) }
    KitSection(stringResource(R.string.gallery_tabs_title)) {
        Sample(stringResource(R.string.gallery_tabs_underline)) { KitTabs(few, underline, { underline = it }) }
        Sample(stringResource(R.string.gallery_tabs_segmented)) { KitTabs(few, segmented, { segmented = it }, style = TabStyle.Segmented) }
        Sample(stringResource(R.string.gallery_long_text)) { KitTabs(long, many, { many = it }) }
    }
    KitSection(stringResource(R.string.gallery_choice_title), description = stringResource(R.string.gallery_choice_note)) {
        Sample(stringResource(R.string.gallery_choice_segments)) { KitChoice(options, choice, { names[it] }, { choice = it }) }
        Sample(stringResource(R.string.gallery_choice_list)) { KitChoice(options, list, { wide[it] }, { list = it }) }
    }
    KitSection(stringResource(R.string.gallery_stepper_title)) {
        for (current in STEPPER_POSITIONS) {
            Sample(stringResource(R.string.gallery_stepper_at, current + 1, STEPS)) { KitStepper(STEPS, current) }
        }
    }
}

private const val CHOICES = 3
private const val STEPS = 4
private val STEPPER_POSITIONS = listOf(0, 2, 3)
