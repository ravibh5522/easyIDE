package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import dev.easyide.app.ui.kit.Kit

/**
 * A project's tile: its initials over a tint that is a pure function of its name, so it is
 * recognisable at a glance and identical on every launch. The tint is a categorical lane colour
 * from the theme, flat and translucent so the letters, in the ordinary text colour, keep contrast.
 */
@Composable
internal fun ProjectMonogram(name: String, size: Dp, modifier: Modifier = Modifier) {
    val lanes = Kit.colors.lanes
    val tint = lanes[monogramBucket(name, lanes.size)]
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(Kit.radius.m))
            .background(tint.copy(alpha = HomeMetrics.MONOGRAM_TINT_ALPHA))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(monogramLetters(name), style = Kit.type.titleSmall.copy(color = Kit.colors.plainText))
    }
}
