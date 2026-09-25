package dev.easyide.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp

private val LocalInGroup = staticCompositionLocalOf { false }

/**
 * A raised surface with a hairline border and no shadow (kit.md 3.1). Children that want a
 * separator call [kitGroupSeparator]; [KitRow] does. A group inside a group renders its content
 * flat, so the "no card in a card" rule (U-LAY-08) holds by construction.
 */
@Composable
fun KitGroup(
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalInGroup.current) {
        Column(modifier.fillMaxWidth(), content = content)
        return
    }
    val colors = Kit.colors
    val shape = RoundedCornerShape(Kit.radius.m)
    val fill = if (tone == Tone.Neutral) colors.raised else tone.container(colors)
    val edge = if (tone == Tone.Neutral) colors.panelBorder else tone.content(colors)
    val hairline = Kit.hairline
    Box(modifier.fillMaxWidth().clip(shape).background(fill).border(hairline, edge, shape)) {
        CompositionLocalProvider(LocalInGroup provides true) {
            // Every separator is a child's top rule. Shifting the column up by one hairline pushes
            // the first child's rule out of the clip, so the group needs no first/last bookkeeping.
            Column(Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val shift = hairline.roundToPx()
                layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) { placeable.place(0, -shift) }
            }, content = content)
        }
    }
}

/** A hairline along the top of a child of a [KitGroup], starting [inset] from the start edge (the text edge). */
@Composable
fun Modifier.kitGroupSeparator(inset: Dp): Modifier {
    if (!LocalInGroup.current) return this
    val color = Kit.colors.panelBorder
    val hairline = Kit.hairline
    return drawBehind {
        val x = inset.toPx()
        drawRect(color, Offset(x, 0f), Size(size.width - x, hairline.toPx()))
    }
}
