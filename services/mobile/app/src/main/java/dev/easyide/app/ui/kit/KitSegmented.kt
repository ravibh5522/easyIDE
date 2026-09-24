package dev.easyide.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/** A divider sits after every segment except the last. */
internal fun dividerAfter(index: Int, count: Int): Boolean = index < count - 1

/**
 * Each segment is as wide as its label; the width the control has beyond that is shared evenly.
 * When the labels alone are wider than [minTotal] the segments keep their natural widths and the
 * control scrolls, so a label is never cut to "Environm" (U-DEN-05).
 */
internal fun segmentWidths(natural: List<Int>, minTotal: Int): List<Int> {
    val total = natural.sum()
    if (natural.isEmpty() || total >= minTotal) return natural
    val extra = minTotal - total
    return natural.mapIndexed { i, w -> w + extra / natural.size + if (i < extra % natural.size) 1 else 0 }
}

@Composable
internal fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val colors = Kit.colors
    val shape = RoundedCornerShape(Kit.radius.s)
    val wash = Tone.Accent.container(colors, colors.background)
    BoxWithConstraints(modifier.clip(shape).border(Kit.hairline, colors.panelBorder, shape)) {
        val available = maxWidth
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Layout(
                content = {
                    labels.forEachIndexed { i, label ->
                        val on = i == selected
                        val interaction = remember { MutableInteractionSource() }
                        val flags = interaction.collectFlags()
                        Box(
                            modifier = Modifier
                                .heightIn(min = Kit.control.fieldHeight)
                                .background(if (on) wash else colors.background)
                                .selectable(on, interaction, null, role = Role.Tab, onClick = { onSelect(i) })
                                .kitStateLayer(flags, true, colors.plainText)
                                .kitFocusRing(flags.focused, shape)
                                .drawBehind {
                                    if (dividerAfter(i, labels.size)) drawRect(colors.panelBorder, Offset(size.width - Kit.hairline.toPx(), 0f), Size(Kit.hairline.toPx(), size.height))
                                }
                                .padding(horizontal = Kit.control.hPad),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(label, style = Kit.text.title.copy(color = if (on) colors.accent else colors.plainText), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                modifier = Modifier.widthIn(min = available),
            ) { measurables, constraints ->
                val widths = segmentWidths(measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }, constraints.minWidth)
                val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints(widths[i], widths[i], 0, constraints.maxHeight)) }
                val height = placeables.maxOfOrNull { it.height } ?: 0
                layout(widths.sum(), height) {
                    var x = 0
                    placeables.forEach { it.place(x, 0); x += it.width }
                }
            }
        }
    }
}
