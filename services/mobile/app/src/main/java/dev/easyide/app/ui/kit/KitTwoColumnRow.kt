package dev.easyide.app.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp

private const val DESCRIPTION_LINES = 3

/** A settings-style row stacks its control under the label when the row is narrower than the threshold. */
internal fun twoColumnStacked(rowWidth: Dp, threshold: Dp): Boolean = rowWidth < threshold

/** The label column is a fixed share of the row, clamped, so labels line up down a page whatever the text is (U-DEN-02). */
internal fun labelColumnWidth(rowWidth: Dp): Dp = (rowWidth * KitSizes.LABEL_COLUMN_SHARE).coerceIn(KitSizes.labelColumnMin, KitSizes.labelColumnMax)

/**
 * A settings or detail row for wide windows (U-DEN-06): the title and its description on the left
 * in a fixed-width column, the [control] on the right, right-aligned in the rest. Below
 * `KitSizes.twoColumnStackBelow` (a narrow window, or a narrow pane on a wide one) the control
 * stacks under the label. The description may wrap: this is a page row, not a list row.
 */
@Composable
fun KitTwoColumnRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    id: String? = null,
    control: @Composable () -> Unit,
) {
    val dims = Kit.control
    val space = Kit.space
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .then(if (id != null) Modifier.kitTag(id) else Modifier)
            .kitGroupSeparator(dims.hPad)
            .defaultMinSize(minHeight = dims.rowHeight)
            .padding(horizontal = dims.hPad, vertical = space.xs),
        contentAlignment = Alignment.CenterStart,
    ) {
        val available = maxWidth
        val label = @Composable { LabelBlock(title, description) }
        if (twoColumnStacked(available, KitSizes.twoColumnStackBelow)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(space.xs)) {
                label()
                control()
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.m)) {
                Box(Modifier.width(labelColumnWidth(available))) { label() }
                Box(Modifier.weight(1f), Alignment.CenterEnd) { control() }
            }
        }
    }
}

@Composable
private fun LabelBlock(title: String, description: String?) {
    Column {
        BasicText(title, style = Kit.text.title.copy(color = Kit.colors.plainText))
        if (description != null) {
            BasicText(description, style = Kit.text.caption.copy(color = Kit.colors.textMuted), maxLines = DESCRIPTION_LINES, overflow = TextOverflow.Ellipsis)
        }
    }
}
