package dev.easyide.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import dev.easyide.app.R
import dev.easyide.app.ui.theme.EditorColors

/** What the border says about the field. Disabled beats error beats focus. */
internal enum class FieldEdge { Idle, Focused, Error, Disabled }

internal fun fieldEdge(enabled: Boolean, focused: Boolean, error: String?): FieldEdge = when {
    !enabled -> FieldEdge.Disabled
    !error.isNullOrEmpty() -> FieldEdge.Error
    focused -> FieldEdge.Focused
    else -> FieldEdge.Idle
}

/** Focus and error are the 2dp ring; idle and disabled are the hairline. */
internal fun FieldEdge.color(colors: EditorColors): Color = when (this) {
    FieldEdge.Focused -> colors.focus
    FieldEdge.Error -> colors.error
    else -> colors.panelBorder
}

internal fun FieldEdge.isRing(): Boolean = this == FieldEdge.Focused || this == FieldEdge.Error

/** A multi-line field keeps its Enter key, so it has no clear button; nothing to clear when empty or locked. */
internal fun fieldShowsClear(value: String, enabled: Boolean, readOnly: Boolean, singleLine: Boolean): Boolean =
    singleLine && enabled && !readOnly && value.isNotEmpty()

/** Flat text field: [label] above, hairline border that becomes the focus ring, [error] below with a 2dp rule. */
@Composable
fun KitField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    hint: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    mono: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    /** Applied to the text input itself (focus, key events), where [modifier] applies to the whole field. */
    inputModifier: Modifier = Modifier,
) {
    val colors = Kit.colors
    val interaction = remember { MutableInteractionSource() }
    val edge = fieldEdge(enabled, interaction.collectFlags().focused, error)
    val shape = RoundedCornerShape(Kit.radius.s)
    val base = if (mono) Kit.text.mono else Kit.text.body
    val text = base.copy(color = if (enabled) colors.plainText else colors.textDisabled)

    Column(modifier.kitTag("field")) {
        if (label != null) {
            BasicText(label, style = Kit.text.caption.copy(color = colors.textMuted))
            Spacer(Modifier.height(Kit.space.xs))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().then(inputModifier).semantics {
                if (label != null) contentDescription = label
                if (!error.isNullOrEmpty()) error(error)
            },
            enabled = enabled,
            readOnly = readOnly,
            textStyle = text,
            keyboardOptions = keyboard,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            singleLine = singleLine,
            cursorBrush = SolidColor(colors.cursor),
            interactionSource = interaction,
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .heightIn(min = Kit.control.fieldHeight)
                        .clip(shape)
                        .background(colors.background)
                        .border(if (edge.isRing()) Kit.marker else Kit.hairline, edge.color(colors), shape),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                ) {
                    Box(Modifier.weight(1f).padding(horizontal = Kit.control.hPad, vertical = Kit.space.xs), Alignment.CenterStart) {
                        if (value.isEmpty() && hint != null) BasicText(hint, style = text.copy(color = colors.textMuted))
                        inner()
                    }
                    if (fieldShowsClear(value, enabled, readOnly, singleLine)) {
                        KitIconButton(Icons.Filled.Close, stringResource(R.string.kitin_clear), { onValueChange("") }, Modifier.size(Kit.control.fieldHeight))
                    }
                    trailing?.invoke()
                }
            },
        )
        if (!error.isNullOrEmpty()) {
            BasicText(
                text = error,
                style = Kit.text.caption.copy(color = colors.error),
                modifier = Modifier
                    .padding(top = Kit.space.xs)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .drawBehind { drawRect(colors.error, size = Size(Kit.marker.toPx(), size.height)) }
                    .padding(start = Kit.space.s),
            )
        }
    }
}
