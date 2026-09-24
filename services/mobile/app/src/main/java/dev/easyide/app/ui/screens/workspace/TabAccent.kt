package dev.easyide.app.ui.screens.workspace

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import dev.easyide.app.ui.theme.Stroke

/**
 * The accent bar across the top of the active tab - the one place the accent marks which tab has
 * focus. Drawn, not laid out, so switching tabs never shifts a label. The terminal's tab strip uses it.
 */
internal fun Modifier.tabAccentBar(active: Boolean, color: Color): Modifier =
    if (!active) this else drawBehind {
        drawRect(color, size = Size(size.width, Stroke.accentBar.toPx()))
    }
