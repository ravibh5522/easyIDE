package dev.easyide.app.ui.screens.workspace

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import dev.easyide.app.data.settings.ScrollbarVisibility
import dev.easyide.app.ui.screens.workspace.decor.DecorationMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * A thin overlay scrollbar for the editor (`editor.scrollbar.vertical`). `auto` shows it while
 * the text scrolls and fades it out shortly after, `visible` keeps it, `hidden` draws nothing.
 * It overlays the text instead of taking a column, so the editor width does not change.
 */
@Composable
internal fun Modifier.editorScrollbar(scroll: ScrollState, visibility: ScrollbarVisibility, color: Color): Modifier {
    if (visibility == ScrollbarVisibility.HIDDEN) return this
    val alpha = remember { Animatable(if (visibility == ScrollbarVisibility.VISIBLE) 1f else 0f) }
    LaunchedEffect(visibility, scroll) {
        if (visibility == ScrollbarVisibility.VISIBLE) {
            alpha.snapTo(1f)
            return@LaunchedEffect
        }
        snapshotFlow { scroll.value }.collectLatest {
            alpha.snapTo(1f)
            delay(DecorationMetrics.SCROLLBAR_FADE_DELAY_MS)
            alpha.animateTo(0f, tween(DecorationMetrics.SCROLLBAR_FADE_MS))
        }
    }
    return drawWithContent {
        drawContent()
        val travel = scroll.maxValue
        if (travel <= 0 || alpha.value <= 0f) return@drawWithContent
        val track = size.height
        val content = track + travel
        val thumb = (track * track / content).coerceIn(DecorationMetrics.scrollbarMinThumb.toPx().coerceAtMost(track), track)
        val top = (track - thumb) * scroll.value / travel
        val width = DecorationMetrics.scrollbarWidth.toPx()
        drawRect(color.copy(alpha = color.alpha * DecorationMetrics.SCROLLBAR_THUMB_ALPHA * alpha.value), Offset(size.width - width, top), Size(width, thumb))
    }
}
