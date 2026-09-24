package dev.easyide.app.ui.screens.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.foundation.HeightClass
import dev.easyide.app.ui.foundation.MotionTokens
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.foundation.motionSpec
import dev.easyide.app.ui.theme.Stroke
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.sandbox.files.FileNode

// The workspace's side panes (explorer / source control, docked or overlaid) and the
// dividers and stage sizes the screen lays them out with.

@Composable
internal fun RowScope.ExplorerColumn(
    visible: Boolean,
    width: Dp,
    uiState: WorkspaceUiState,
    callbacks: WorkspaceCallbacks,
    sidePanel: SidePanel,
    gitState: GitPanelState,
    gitCallbacks: SourceControlCallbacks,
    onNodeMenu: (FileNode) -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            animationSpec = motionSpec(easing = MotionTokens.EnterEasing),
            initialOffsetX = { -it },
        ) + fadeIn(motionSpec()),
        exit = slideOutHorizontally(animationSpec = motionSpec(), targetOffsetX = { -it }) +
            fadeOut(motionSpec()),
    ) {
        Row {
            when (sidePanel) {
                SidePanel.EXPLORER -> FileTreePane(
                    state = uiState,
                    onFileOpened = callbacks.onFileOpened,
                    onDirectoryToggled = callbacks.onDirectoryToggled,
                    onNodeMenu = onNodeMenu,
                    onNewFile = onNewFile,
                    onNewFolder = onNewFolder,
                    onRefresh = callbacks.onRefreshTree,
                    modifier = Modifier.width(width).fillMaxHeight(),
                )
                SidePanel.SOURCE_CONTROL -> SourceControlPane(
                    state = gitState,
                    callbacks = gitCallbacks,
                    modifier = Modifier.width(width).fillMaxHeight(),
                )
            }
            VerticalDivider()
        }
    }
}

/** Compact-width explorer, drawn over the editor. */
@Composable
internal fun ExplorerOverlay(
    visible: Boolean,
    width: Dp,
    uiState: WorkspaceUiState,
    callbacks: WorkspaceCallbacks,
    sidePanel: SidePanel,
    gitState: GitPanelState,
    gitCallbacks: SourceControlCallbacks,
    onNodeMenu: (FileNode) -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            animationSpec = motionSpec(easing = MotionTokens.EnterEasing),
            initialOffsetX = { -it },
        ) + fadeIn(motionSpec()),
        exit = slideOutHorizontally(animationSpec = motionSpec(), targetOffsetX = { -it }) +
            fadeOut(motionSpec()),
    ) {
        Row {
            when (sidePanel) {
                SidePanel.EXPLORER -> FileTreePane(
                    state = uiState,
                    onFileOpened = callbacks.onFileOpened,
                    onDirectoryToggled = callbacks.onDirectoryToggled,
                    onNodeMenu = onNodeMenu,
                    onNewFile = onNewFile,
                    onNewFolder = onNewFolder,
                    onRefresh = callbacks.onRefreshTree,
                    modifier = Modifier.width(width).fillMaxHeight(),
                )
                SidePanel.SOURCE_CONTROL -> SourceControlPane(
                    state = gitState,
                    callbacks = gitCallbacks,
                    modifier = Modifier.width(width).fillMaxHeight(),
                )
            }
            VerticalDivider()
        }
    }
}

@Composable
internal fun VerticalDivider() {
    Box(
        modifier = Modifier
            .width(Stroke.hairline)
            .fillMaxHeight()
            .background(editorColors.panelBorder),
    )
}

@Composable
internal fun HorizontalDividerLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Stroke.hairline)
            .background(editorColors.panelBorder),
    )
}

internal fun WidthClass.explorerWidth(): Dp = when (this) {
    WidthClass.COMPACT -> COMPACT_EXPLORER_DP.dp
    WidthClass.MEDIUM -> MEDIUM_EXPLORER_DP.dp
    WidthClass.EXPANDED -> EXPANDED_EXPLORER_DP.dp
}

internal fun HeightClass.terminalHeight(): Dp =
    if (this == HeightClass.COMPACT) COMPACT_TERMINAL_DP.dp else REGULAR_TERMINAL_DP.dp

private const val COMPACT_EXPLORER_DP = 240
private const val MEDIUM_EXPLORER_DP = 240
private const val EXPANDED_EXPLORER_DP = 280
private const val COMPACT_TERMINAL_DP = 160
private const val REGULAR_TERMINAL_DP = 260
