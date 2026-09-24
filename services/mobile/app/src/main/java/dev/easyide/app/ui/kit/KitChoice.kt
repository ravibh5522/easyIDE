package dev.easyide.app.ui.kit

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

internal enum class ChoiceLayout { Segmented, List }

private const val SEGMENTS_MIN = 2
private const val SEGMENTS_MAX = 4

/** Total label length that still fits one row of segments on a phone at the default scale. */
private const val SEGMENT_CHARS_MAX = 32

/** A few short values sit side by side; anything else is a list of radio rows. */
internal fun choiceLayout(labels: List<String>): ChoiceLayout =
    if (labels.size in SEGMENTS_MIN..SEGMENTS_MAX && labels.sumOf { it.length } <= SEGMENT_CHARS_MAX) ChoiceLayout.Segmented else ChoiceLayout.List

/** Picks one value of a small enum; the layout follows [choiceLayout]. Both variants report [Role.Tab] or [Role.RadioButton] with state. */
@Composable
fun <T> KitChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = options.map(label)
    val at = options.indexOf(selected)
    if (choiceLayout(labels) == ChoiceLayout.Segmented) {
        KitTabs(labels, at, { onSelect(options[it]) }, modifier.kitTag("choice"), TabStyle.Segmented)
        return
    }
    Column(modifier.kitTag("choice").selectableGroup()) {
        options.forEachIndexed { i, option ->
            val interaction = remember { MutableInteractionSource() }
            val flags = interaction.collectFlags()
            val shape = RoundedCornerShape(Kit.radius.xs)
            Row(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = Kit.control.rowHeight)
                    .selectable(i == at, interaction, null, role = Role.RadioButton, onClick = { onSelect(option) })
                    .kitStateLayer(flags, true, Kit.colors.plainText)
                    .kitFocusRing(flags.focused, shape)
                    .padding(horizontal = Kit.space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Kit.space.m),
            ) {
                KitToggle(i == at, null, kind = ToggleKind.Radio)
                BasicText(labels[i], style = Kit.text.body.copy(color = Kit.colors.plainText))
            }
        }
    }
}
