package dev.easyide.app.ui.shell.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.screens.workspace.layout.LayoutTokens
import dev.easyide.app.ui.screens.workspace.layout.PaneLimits
import dev.easyide.app.ui.shell.host.PaneSplitter
import dev.easyide.app.ui.shell.host.PanelHost
import dev.easyide.app.ui.shell.host.PanelRendererRegistry

/** A side panel's width rule and what its drag edge does; the same shape for the primary and the secondary panel. */
class SideSizing(
    val widthDp: Float,
    val limits: PaneLimits,
    val ceilingDp: Float,
    val onSize: (Float) -> Unit,
    val onReset: () -> Unit,
)

/** A panel docked beside the stage, with its drag edge on the side that faces the stage. */
@Composable
fun DockedSidePanel(
    containerId: String?,
    panels: PanelRendererRegistry,
    sizing: SideSizing,
    atEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier) {
        val splitter: @Composable () -> Unit = {
            PaneSplitter(sizing.widthDp, sizing.limits, sizing.ceilingDp, sizing.onSize, sizing.onReset, growsTowardsStart = atEnd)
        }
        if (atEnd) splitter()
        PanelHost(containerId, panels, Modifier.width(sizing.widthDp.dp).fillMaxHeight())
        if (!atEnd) splitter()
    }
}

/**
 * A side panel as a modal sheet over the stage (a phone; layout-spec.md section 5): 88% of the window,
 * at most 360dp, with a scrim that closes it when tapped. The stage behind stays composed, so what
 * the user was editing is where they left it. Slide and fade follow the pane motion and are instant
 * under reduce motion (their duration is 0).
 */
@Composable
fun SideSheet(
    open: Boolean,
    atEnd: Boolean,
    containerId: String?,
    panels: PanelRendererRegistry,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = Kit.motion
    val edge = if (atEnd) Alignment.CenterEnd else Alignment.CenterStart
    val direction = if (atEnd) 1 else -1
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sheetWidth = minOf(maxWidth * LayoutTokens.DRAWER_WIDTH_FRACTION, LayoutTokens.drawerMaxWidth)
        AnimatedVisibility(open, enter = fadeIn(tween(motion.paneMs)), exit = fadeOut(tween(motion.paneMs))) {
            Box(
                Modifier.fillMaxSize().kitTag("sheet-scrim")
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = LayoutTokens.SCRIM_ALPHA))
                    .clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClickLabel = stringResource(R.string.wshell_sheet_scrim), onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            open,
            Modifier.align(edge),
            enter = slideInHorizontally(tween(motion.paneMs, easing = motion.enter)) { it * direction },
            exit = slideOutHorizontally(tween(motion.paneMs, easing = motion.exit)) { it * direction },
        ) {
            PanelHost(containerId, panels, Modifier.width(sheetWidth).fillMaxHeight().kitTag("sheet"))
        }
    }
}
