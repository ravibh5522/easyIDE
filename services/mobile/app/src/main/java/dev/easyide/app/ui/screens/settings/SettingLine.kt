package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitTwoColumnRow
import dev.easyide.app.ui.kit.kitMarker
import dev.easyide.app.ui.kit.kitPressable

/**
 * The line of one setting, one anatomy for every kind of row: the title with its description, and
 * the [control] at the end. On a wide page it is a [KitTwoColumnRow], label column and control
 * column, so all controls of a page share one right edge. On a narrow page a short control shares
 * the line of a [KitRow] (description under the title, as Comfortable density allows), and a [wide]
 * one (a text field) stacks under its label. The modified dot sits in the start gutter of either.
 */
@Composable
internal fun SettingLine(
    title: String,
    description: String?,
    id: String,
    modified: Boolean,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    marked: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    control: @Composable () -> Unit,
) {
    ModifiedGutter(modified) {
        if (LocalRowLayout.current == RowLayout.INLINE && !wide) {
            KitRow(
                title = title, subtitle = description, modifier = modifier, trailing = control, onClick = onClick,
                selected = marked, enabled = enabled, secondLine = true, id = id,
            )
        } else {
            val press = if (onClick != null) Modifier.kitPressable(onClick, enabled) else Modifier
            KitTwoColumnRow(title, modifier.then(press).kitMarker(marked), description, id, control)
        }
    }
}

/** A short control with this layer's reset to its left; the control keeps the right edge whether or not the reset shows. */
@Composable
internal fun WithReset(modified: Boolean, onReset: () -> Unit, control: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
        if (modified) ResetButton(onReset)
        control()
    }
}

/** A field-like control: the field takes the control column, and the reset slot is reserved so every field starts at one edge. */
@Composable
internal fun WideWithReset(modified: Boolean, onReset: () -> Unit, control: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
        Box(Modifier.weight(1f)) { control() }
        Box(Modifier.width(Kit.control.hitBox), Alignment.Center) { if (modified) ResetButton(onReset) }
    }
}

@Composable
internal fun ResetButton(onReset: () -> Unit) {
    KitIconButton(Icons.Filled.Restore, stringResource(R.string.setting_reset), onReset)
}
