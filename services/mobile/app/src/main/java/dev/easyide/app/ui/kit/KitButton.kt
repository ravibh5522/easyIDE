package dev.easyide.app.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.theme.EditorColors
import dev.easyide.app.ui.theme.IconSize
import kotlinx.coroutines.delay

/** Primary is the one accent fill of a view; Danger is error-coloured text, never a fill. */
enum class KitButtonStyle { Primary, Secondary, Ghost, Danger }

/** A labelled action, as banners and dialogs take them. */
data class KitAction(val label: String, val onClick: () -> Unit)

internal data class ButtonPaint(val fill: Color?, val content: Color, val border: Color?)

internal fun buttonPaint(style: KitButtonStyle, enabled: Boolean, colors: EditorColors): ButtonPaint = when {
    !enabled -> when (style) {
        KitButtonStyle.Primary -> ButtonPaint(colors.raised, colors.textDisabled, null)
        KitButtonStyle.Secondary -> ButtonPaint(null, colors.textDisabled, colors.panelBorder)
        else -> ButtonPaint(null, colors.textDisabled, null)
    }
    style == KitButtonStyle.Primary -> ButtonPaint(colors.accent, colors.onAccent, null)
    style == KitButtonStyle.Secondary -> ButtonPaint(null, colors.plainText, colors.panelBorder)
    style == KitButtonStyle.Ghost -> ButtonPaint(null, colors.plainText, null)
    else -> ButtonPaint(null, colors.error, null)
}

@Composable
fun KitButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: KitButtonStyle = KitButtonStyle.Primary,
    icon: ImageVector? = null,
    loading: Boolean = false,
    enabled: Boolean = true,
    large: Boolean = false,
    fillWidth: Boolean = false,
) {
    val paint = buttonPaint(style, enabled, Kit.colors)
    val interaction = remember { MutableInteractionSource() }
    val flags = interaction.collectFlags()
    val height = if (large) KitSizes.buttonLarge else Kit.control.buttonHeight
    val shape = RoundedCornerShape(Kit.metrics.radiusFor(Kit.radius.s, height))
    val loadingText = stringResource(R.string.kitin_loading)

    Box(
        modifier = modifier
            .kitTag("button")
            .kitHitSlop()
            .clickable(interaction, null, enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .semantics { if (loading) stateDescription = loadingText },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .defaultMinSize(minHeight = height)
                .clip(shape)
                .then(if (paint.fill != null) Modifier.background(paint.fill) else Modifier)
                .then(if (paint.border != null) Modifier.border(Kit.hairline, paint.border, shape) else Modifier)
                .kitStateLayer(flags, enabled && !loading, paint.content)
                .kitFocusRing(flags.focused, shape)
                .padding(horizontal = Kit.control.hPad),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.alpha(if (loading) 0f else 1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Kit.space.s),
            ) {
                icon?.let { Image(it, null, Modifier.size(IconSize.s), colorFilter = ColorFilter.tint(paint.content)) }
                BasicText(text, style = Kit.text.title.copy(color = paint.content), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (loading) Sweep(paint.content)
        }
    }
}

private const val SWEEP_CELLS = 8

/** The cell the block sits on after [step]; wraps, so the sweep restarts at the first cell. */
internal fun nextSweepCell(step: Int, cells: Int = SWEEP_CELLS): Int = (step + 1) % cells

/** A text-cursor block stepping across eight cells in place of the label (identity.md 10); solid at rest under reduce motion. */
@Composable
private fun Sweep(color: Color) {
    var cell by remember { mutableIntStateOf(0) }
    val animate = !Kit.motion.reduce
    LaunchedEffect(animate) {
        while (animate) {
            delay(SWEEP_STEP_MS)
            cell = nextSweepCell(cell)
        }
    }
    val cellW = Kit.space.xs
    val gap = Kit.space.xxs
    Canvas(Modifier.size(cellW * SWEEP_CELLS + gap * (SWEEP_CELLS - 1), Kit.space.m)) {
        repeat(SWEEP_CELLS) { i ->
            val alpha = if (i == cell) 1f else KitStateLayer.TRACK
            drawRect(color.copy(alpha = alpha), Offset((cellW + gap).toPx() * i, 0f), Size(cellW.toPx(), size.height))
        }
    }
}

private const val SWEEP_STEP_MS = 90L
