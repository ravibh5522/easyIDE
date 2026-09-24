package dev.easyide.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp

/**
 * A placeholder bar standing in for text that is still loading, sized by the
 * caller to the line it replaces so the layout does not jump when content lands.
 *
 * Static on purpose: motion confirms cause and effect and never loops at idle
 * (ux-overhaul Pillar 3 "Motion"), and a loading list is idle from the user's
 * side. Hidden from accessibility; the surrounding screen announces loading.
 */
@Composable
fun SkeletonBar(color: Color, height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(height)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color)
            .clearAndSetSemantics { },
    )
}
