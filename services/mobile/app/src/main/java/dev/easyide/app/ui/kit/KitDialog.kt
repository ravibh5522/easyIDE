package dev.easyide.app.ui.kit

import android.view.Gravity
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.props.Handedness

enum class DialogPlacement { Centered, BottomSheet }

/** A phone-width window has no room for a floating card: the dialog becomes a sheet at the bottom. */
internal fun dialogPlacement(width: WidthClass): DialogPlacement =
    if (width.isCompact) DialogPlacement.BottomSheet else DialogPlacement.Centered

/** Dismiss left, confirm right; a left-handed layout mirrors them so the primary action sits under the thumb. */
internal fun <T> dialogActionOrder(dismiss: T?, confirm: T?, handedness: Handedness): List<T> {
    val ordered = listOfNotNull(dismiss, confirm)
    return if (handedness == Handedness.LEFT) ordered.asReversed() else ordered
}

/**
 * One question in a platform dialog window, so focus is trapped in it and Back dismisses it
 * before anything else. Calm on purpose: hairline, overlay tone, no icon, no motif. A [Tone.Danger]
 * dialog colours only the confirm text; nothing here is ever a red block.
 */
@Composable
fun KitDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirm: KitAction? = null,
    dismiss: KitAction? = null,
    tone: Tone = Tone.Neutral,
    content: @Composable ColumnScope.() -> Unit,
) {
    val placement = dialogPlacement(LocalWindowSize.current.width)
    val sheet = placement == DialogPlacement.BottomSheet
    val colors = Kit.colors
    val radius = Kit.radius.l
    val shape = if (sheet) RoundedCornerShape(topStart = radius, topEnd = radius) else RoundedCornerShape(radius)
    val actions = dialogActionOrder(
        dismiss?.let { it to KitButtonStyle.Ghost },
        confirm?.let { it to if (tone == Tone.Danger) KitButtonStyle.Danger else KitButtonStyle.Primary },
        Kit.feel.handedness,
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = !sheet)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setGravity(if (sheet) Gravity.BOTTOM else Gravity.CENTER)
            if (sheet) window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        Column(
            modifier = modifier
                .kitTag("dialog")
                .widthIn(max = KitSizes.dialogMaxWidth)
                .fillMaxWidth()
                .clip(shape)
                .background(colors.overlay)
                .border(Kit.hairline, colors.panelBorder, shape)
                .semantics { paneTitle = title }
                .padding(Kit.space.l),
            verticalArrangement = Arrangement.spacedBy(Kit.space.m),
        ) {
            BasicText(title, Modifier.semantics { heading() }, style = Kit.type.titleMedium.copy(color = colors.plainText))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
            if (actions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(Kit.space.s, Alignment.End)) {
                    actions.forEach { (action, style) ->
                        KitButton(action.label, action.onClick, Modifier.then(if (sheet) Modifier.weight(1f) else Modifier), style, large = sheet)
                    }
                }
            }
        }
    }
}
