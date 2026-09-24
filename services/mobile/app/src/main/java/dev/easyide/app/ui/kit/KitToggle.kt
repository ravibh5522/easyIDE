package dev.easyide.app.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import dev.easyide.app.ui.theme.EditorColors

/** Switch for settings that apply at once, Check for independent choices, Radio for one of several. */
enum class ToggleKind { Switch, Check, Radio }

internal data class TogglePaint(val fill: Color, val border: Color, val mark: Color)

/** On is the accent (a filled block; a radio is an accent ring and dot); off is an outline in muted text. */
internal fun togglePaint(kind: ToggleKind, checked: Boolean, enabled: Boolean, colors: EditorColors): TogglePaint = when {
    !enabled -> TogglePaint(Color.Transparent, colors.textDisabled, colors.textDisabled)
    !checked -> TogglePaint(Color.Transparent, colors.textMuted, colors.textMuted)
    kind == ToggleKind.Radio -> TogglePaint(Color.Transparent, colors.accent, colors.accent)
    else -> TogglePaint(colors.accent, colors.accent, colors.onAccent)
}

/**
 * Drawn, not Material. With [onCheckedChange] null the glyph is display only: its row owns the
 * click, the role and the state, so it adds no target or semantics of its own.
 */
@Composable
fun KitToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    kind: ToggleKind = ToggleKind.Switch,
    enabled: Boolean = true,
) {
    val paint = togglePaint(kind, checked, enabled, Kit.colors)
    val interaction = remember { MutableInteractionSource() }
    val flags = interaction.collectFlags()
    val haptics = rememberHaptics()
    val width = if (kind == ToggleKind.Switch) KitSizes.switchWidth else KitSizes.glyph
    val corner = if (kind == ToggleKind.Radio) KitSizes.glyph / 2 else Kit.metrics.radiusFor(Kit.radius.xs, KitSizes.glyph)
    val shape = RoundedCornerShape(corner)
    val progress by animateFloatAsState(if (checked) 1f else 0f, tween(Kit.motion.standardMs, easing = Kit.motion.enter), label = "toggle")
    val change: (Boolean) -> Unit = { next -> haptics.play(HapticEvent.Toggle); onCheckedChange?.invoke(next) }

    val target = when {
        onCheckedChange == null -> Modifier
        kind == ToggleKind.Radio -> Modifier.kitTouchFloor().selectable(checked, interaction, null, enabled, Role.RadioButton) { change(true) }
        else -> Modifier.kitTouchFloor().toggleable(checked, interaction, null, enabled, if (kind == ToggleKind.Switch) Role.Switch else Role.Checkbox, change)
    }
    Box(modifier.kitTag("toggle").then(target), Alignment.Center) {
        Canvas(
            Modifier.size(width, KitSizes.glyph).clip(shape)
                .kitStateLayer(flags, enabled && onCheckedChange != null, paint.mark)
                .kitFocusRing(flags.focused, shape),
        ) { drawGlyph(kind, paint, progress, checked, corner.toPx()) }
    }
}

private fun DrawScope.drawGlyph(kind: ToggleKind, paint: TogglePaint, progress: Float, checked: Boolean, corner: Float) {
    val stroke = (if (kind == ToggleKind.Switch) Kit.hairline else Kit.marker).toPx()
    val half = stroke / 2
    drawRoundRect(paint.fill, cornerRadius = CornerRadius(corner))
    drawRoundRect(paint.border, Offset(half, half), Size(size.width - stroke, size.height - stroke), CornerRadius(corner), Stroke(stroke))
    when (kind) {
        ToggleKind.Switch -> {
            val t = KitSizes.switchThumb.toPx()
            val inset = (size.height - t) / 2
            drawRoundRect(paint.mark, Offset(inset + (size.width - t - 2 * inset) * progress, inset), Size(t, t), CornerRadius(minOf(corner, t / 2)))
        }
        ToggleKind.Check -> if (checked) {
            val w = size.width
            val h = size.height
            val width = Kit.marker.toPx()
            drawLine(paint.mark, Offset(w * 0.25f, h * 0.52f), Offset(w * 0.43f, h * 0.70f), width, StrokeCap.Square)
            drawLine(paint.mark, Offset(w * 0.43f, h * 0.70f), Offset(w * 0.76f, h * 0.32f), width, StrokeCap.Square)
        }
        ToggleKind.Radio -> if (checked) drawCircle(paint.mark, size.height * 0.25f)
    }
}
