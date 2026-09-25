package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitSizes
import dev.easyide.app.ui.kit.twoColumnStacked

/** How a settings row uses the width it gets; one value per page, so every row of a page agrees. */
internal enum class RowLayout {
    /** Label column and control column ([dev.easyide.app.ui.kit.KitTwoColumnRow]). */
    TWO_COLUMN,

    /** A phone: a short control shares the line of the title, a field stacks under it. */
    INLINE,

    /** Too narrow, or the text too large, for a control beside its title: every control goes under its label. */
    STACKED;

    val narrow: Boolean get() = this != TWO_COLUMN
}

/**
 * The two-column layout follows the kit's own stacking threshold. Below it, a control shares the
 * title's line only while the row is at least [inlineMin] wide measured in text size (a row that
 * fits a stepper at 1x text does not at 2x), else it stacks.
 */
internal fun rowLayoutFor(rowWidth: Dp, fontScale: Float, inlineMin: Dp): RowLayout = when {
    !twoColumnStacked(rowWidth, KitSizes.twoColumnStackBelow) -> RowLayout.TWO_COLUMN
    rowWidth / fontScale >= inlineMin -> RowLayout.INLINE
    else -> RowLayout.STACKED
}

internal val LocalRowLayout = compositionLocalOf { RowLayout.TWO_COLUMN }

/** The gutter of an item that sits outside a section's group (a toolbar, a field, a button row): the row padding, so edges line up. */
@Composable
internal fun Modifier.pageGutter(): Modifier = padding(start = Kit.control.hPad, end = Kit.control.hPad, top = Kit.space.s)

/**
 * The frame every page sits in: a centred column no wider than the content maximum, scrolling, and
 * the one place that decides the [RowLayout], from the width the rows really get.
 */
@Composable
internal fun SettingsPageFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        // Section gutter and row padding, both sides: what KitTwoColumnRow measures its stacking against.
        val rowWidth = minOf(maxWidth, Kit.contentMax) - Kit.control.hPad * ROW_INSETS
        CompositionLocalProvider(LocalRowLayout provides rowLayoutFor(rowWidth, fontScale, SettingsMetrics.inlineRowMin)) {
            Column(Modifier.widthIn(max = Kit.contentMax).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Kit.space.xxl)) { content() }
        }
    }
}

private const val ROW_INSETS = 4
