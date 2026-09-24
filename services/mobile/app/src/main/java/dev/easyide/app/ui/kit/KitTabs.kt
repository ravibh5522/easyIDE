package dev.easyide.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight

/** Underline is the editor-tab look; Segmented is a joined outlined control for a few values. */
enum class TabStyle { Underline, Segmented }

/** Arrow-key stepping through tabs, clamped: the strip does not wrap, so the ends are stops. */
internal fun stepTab(selected: Int, count: Int, delta: Int): Int = (selected + delta).coerceIn(0, maxOf(count - 1, 0))

/** The accent bar spans the label, not the padding around it; a tab narrower than its padding gets the full width. */
internal fun indicatorSpan(tabWidth: Float, inset: Float): ClosedFloatingPointRange<Float> =
    if (inset * 2 >= tabWidth) 0f..tabWidth else inset..(tabWidth - inset)

/** A divider sits after every segment except the last. */
internal fun dividerAfter(index: Int, count: Int): Boolean = index < count - 1

@Composable
fun KitTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    style: TabStyle = TabStyle.Underline,
) {
    val haptics = rememberHaptics()
    val select: (Int) -> Unit = { i ->
        if (i != selected) {
            if (style == TabStyle.Segmented) haptics.play(HapticEvent.Snap)
            onSelect(i)
        }
    }
    val keys = Modifier.onKeyEvent { e ->
        val delta = when (e.key) { Key.DirectionRight -> 1; Key.DirectionLeft -> -1; else -> 0 }
        if (delta != 0 && e.type == KeyEventType.KeyDown) select(stepTab(selected, labels.size, delta))
        delta != 0
    }
    val base = modifier.kitTag("tabs").then(keys).selectableGroup()
    if (style == TabStyle.Underline) UnderlineTabs(labels, selected, select, base) else SegmentedTabs(labels, selected, select, base)
}

@Composable
private fun UnderlineTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val colors = Kit.colors
    val line = colors.panelBorder
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .drawBehind { drawRect(line, Offset(0f, size.height - Kit.hairline.toPx()), Size(size.width, Kit.hairline.toPx())) },
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val interaction = remember { MutableInteractionSource() }
            val flags = interaction.collectFlags()
            val inset = Kit.space.l
            Box(
                modifier = Modifier
                    .heightIn(min = maxOf(Kit.control.tab, Kit.metrics.touchFloor))
                    .selectable(on, interaction, null, role = Role.Tab, onClick = { onSelect(i) })
                    .kitStateLayer(flags, true, colors.plainText)
                    .kitFocusRing(flags.focused, RoundedCornerShape(Kit.radius.xs))
                    .drawBehind {
                        if (on) {
                            val span = indicatorSpan(size.width, inset.toPx())
                            drawRect(colors.tabActiveBorder, Offset(span.start, size.height - Kit.marker.toPx()), Size(span.endInclusive - span.start, Kit.marker.toPx()))
                        }
                    }
                    .padding(horizontal = inset),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(label, style = Kit.type.labelLarge.copy(color = if (on) colors.tabActiveText else colors.tabInactiveText))
            }
        }
    }
}

@Composable
private fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val colors = Kit.colors
    val shape = RoundedCornerShape(Kit.radius.s)
    val wash = Tone.Accent.container(colors, colors.background)
    Row(modifier.height(IntrinsicSize.Min).clip(shape).border(Kit.hairline, colors.panelBorder, shape)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val interaction = remember { MutableInteractionSource() }
            val flags = interaction.collectFlags()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Kit.metrics.touchFloor)
                    .background(if (on) wash else colors.background)
                    .selectable(on, interaction, null, role = Role.Tab, onClick = { onSelect(i) })
                    .kitStateLayer(flags, true, colors.plainText)
                    .kitFocusRing(flags.focused, shape)
                    .drawBehind {
                        if (dividerAfter(i, labels.size)) drawRect(colors.panelBorder, Offset(size.width - Kit.hairline.toPx(), 0f), Size(Kit.hairline.toPx(), size.height))
                    }
                    .padding(horizontal = Kit.space.m),
                contentAlignment = Alignment.Center,
            ) {
                val weight = if (on) FontWeight.Medium else FontWeight.Normal
                BasicText(label, style = Kit.type.labelLarge.copy(color = if (on) colors.accent else colors.plainText, fontWeight = weight))
            }
        }
    }
}
