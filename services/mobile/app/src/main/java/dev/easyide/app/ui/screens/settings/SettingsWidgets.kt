package dev.easyide.app.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.theme.EasyIdeFonts

/** Geometry of the small marks this package draws; everything else comes from `Kit.space` and `Kit.control`. */
internal object SettingsMetrics {
    /** The modified dot. */
    val dot = 8.dp

    /** [dev.easyide.app.ui.kit.KitRow]'s leading slot, so a note under a row lines up with the row's text. */
    val leadingSlot = 20.dp

    val stepperValue = 40.dp

    /** A theme card's width in the picker grid. */
    val themeCard = 152.dp

    /** Side of an accent swatch. */
    val swatch = 28.dp
}

/** Mono, muted: the value at the right of a row. */
@Composable
internal fun ValueText(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = monoStyle(Kit.type.bodySmall).copy(color = Kit.colors.textMuted), maxLines = 1)
}

@Composable
internal fun monoStyle(base: TextStyle): TextStyle = base.copy(fontFamily = EasyIdeFonts.mono)

/** A row's leading mark: a dot in the accent while this layer holds a value, blank otherwise so titles stay aligned. */
@Composable
internal fun ModifiedDot(modified: Boolean) {
    if (!modified) return
    val color = Kit.colors.accent
    Canvas(Modifier.size(SettingsMetrics.dot).clearAndSetSemantics { }) { drawCircle(color) }
}

/** A paragraph inside a dialog or a page; [tone] Neutral is body text, any other tone colours it. */
@Composable
internal fun BodyText(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral, mono: Boolean = false) {
    val colors = Kit.colors
    val base = if (mono) monoStyle(Kit.type.bodySmall) else Kit.type.bodyMedium
    BasicText(text, modifier, style = base.copy(color = if (tone == Tone.Neutral) colors.plainText else tone.content(colors)))
}

/** A line under a row, aligned with the row's text: the layer note, the invalid note, why it is read-only. */
@Composable
internal fun RowNote(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier, mono: Boolean = false) {
    val space = Kit.space
    BasicText(
        text,
        modifier.fillMaxWidth().padding(start = space.l + SettingsMetrics.leadingSlot + space.m, end = space.l, bottom = space.s),
        style = (if (mono) monoStyle(Kit.type.bodySmall) else Kit.type.bodySmall).copy(color = tone.content(Kit.colors)),
    )
}

/** A control that needs the row's width (a text field, a choice list), set under the row at its text edge. */
@Composable
internal fun RowBlock(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val space = Kit.space
    Column(
        modifier.fillMaxWidth().padding(start = space.l + SettingsMetrics.leadingSlot + space.m, end = space.l, bottom = space.m),
    ) { content() }
}

@Composable
internal fun Stepper(value: Int, min: Int, max: Int, step: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        KitIconButton(
            Icons.Filled.Remove, stringResource(R.string.setting_decrease),
            { onChange((value - step).coerceAtLeast(min)) }, enabled = enabled && value > min,
        )
        ValueText(value.toString(), Modifier.widthIn(min = SettingsMetrics.stepperValue))
        KitIconButton(
            Icons.Filled.Add, stringResource(R.string.setting_increase),
            { onChange((value + step).coerceAtMost(max)) }, enabled = enabled && value < max,
        )
    }
}

/**
 * The field keeps its own text while editing: each keystroke is written to the store, but echoing
 * the stored value back would arrive a frame late and move the cursor. [isValid] marks the field in
 * error without blocking typing.
 */
@Composable
fun SettingTextField(
    initial: String,
    singleLine: Boolean,
    enabled: Boolean,
    isValid: (String) -> Boolean = { true },
    mono: Boolean = false,
    onChange: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    KitField(
        value = text,
        onValueChange = { text = it; if (isValid(it)) onChange(it) },
        singleLine = singleLine,
        enabled = enabled,
        mono = mono,
        error = if (isValid(text)) null else stringResource(R.string.setting_value_invalid),
        modifier = Modifier.fillMaxWidth(),
    )
}

@StringRes
fun layerLabel(layer: LayerId): Int = when (layer) {
    LayerId.BUILT_IN -> R.string.settings_layer_default
    LayerId.EXTENSION -> R.string.settings_layer_extension
    LayerId.USER -> R.string.settings_layer_user
    LayerId.ENVIRONMENT -> R.string.settings_layer_environment
    LayerId.PROJECT -> R.string.settings_layer_project
}

/** Keeps rows readable instead of stretching across a wide tablet. Kept for the diagnostics screen, which still uses it. */
fun Modifier.contentWidth(): Modifier = this.fillMaxWidth().widthIn(max = MAX_CONTENT_WIDTH)

private val MAX_CONTENT_WIDTH = 720.dp

/** The scrolling column every page sits in: sections stacked with a gutter at the bottom. */
@Composable
internal fun PageColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(bottom = Kit.space.xxl),
        verticalArrangement = Arrangement.spacedBy(Kit.space.none),
    ) { content() }
}
