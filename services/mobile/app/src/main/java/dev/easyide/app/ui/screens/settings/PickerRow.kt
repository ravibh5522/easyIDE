package dev.easyide.app.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitChoice
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow

/**
 * A setting chosen from a list that is not a plain enum (icon theme, key row): the row shows the
 * current value, a tap opens the options inline under it, and reset appears while this layer holds a
 * value. [notes] go under the row (problems the picker knows about).
 */
@Composable
internal fun PickerRow(
    id: String,
    title: String,
    subtitle: String,
    labels: List<String>,
    selected: Int,
    modified: Boolean,
    onReset: () -> Unit,
    onPick: (Int) -> Unit,
    notes: @Composable () -> Unit = {},
) {
    var open by rememberSaveable(id) { mutableStateOf(false) }
    KitRow(
        title = title,
        subtitle = subtitle,
        leading = { ModifiedDot(modified) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
                if (modified) KitIconButton(Icons.Filled.Restore, stringResource(R.string.setting_reset), onReset)
                ValueText(labels.getOrNull(selected) ?: stringResource(R.string.setting_no_value))
            }
        },
        onClick = { open = !open },
        id = "setting:$id",
    )
    notes()
    if (open) RowBlock { KitChoice(labels.indices.toList(), selected, { labels[it] }, { onPick(it); open = false }) }
}
