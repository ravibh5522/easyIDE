package dev.easyide.app.ui.screens.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import dev.easyide.app.session.LayoutSnapshot
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.foundation.WindowSize

/**
 * Which stages are currently showing. Held as UI state rather than derived
 * purely from width so a user's explicit toggle survives rotation - width only
 * sets the default and enforces what physically fits.
 * See docs/ui-shell/arch.md "Stage system".
 */
class WorkspaceStageState(
    leftVisible: Boolean,
    rightVisible: Boolean,
    bottomVisible: Boolean,
    sidePanel: SidePanel = SidePanel.EXPLORER,
) {
    /** What the left stage shows. Lives here, with the stages, so a parked workspace keeps it. */
    var sidePanel by mutableStateOf(sidePanel)

    var leftVisible by mutableStateOf(leftVisible)
        private set

    var rightVisible by mutableStateOf(rightVisible)
        private set

    var bottomVisible by mutableStateOf(bottomVisible)
        private set

    /**
     * Narrow windows can only afford one side stage; opening one closes the
     * other rather than squeezing both into unusable slivers.
     */
    /** Opens the left stage without toggling - the rail switches panels with it. */
    fun showLeft() { leftVisible = true }

    fun toggleLeft(exclusive: Boolean) {
        leftVisible = !leftVisible
        if (leftVisible && exclusive) rightVisible = false
    }

    fun toggleRight(exclusive: Boolean) {
        rightVisible = !rightVisible
        if (rightVisible && exclusive) leftVisible = false
    }

    fun showBottom() {
        bottomVisible = true
    }

    fun toggleBottom() {
        bottomVisible = !bottomVisible
    }

    fun setLayout(left: Boolean, right: Boolean, bottom: Boolean, panel: SidePanel) {
        leftVisible = left
        rightVisible = right
        bottomVisible = bottom
        sidePanel = panel
    }

    fun applyWidthConstraints(width: WidthClass) {
        if (width == WidthClass.COMPACT && leftVisible && rightVisible) {
            rightVisible = false
        }
    }
}

/**
 * The workspace's [WorkspaceStageState], owned by the session rather than by composition so
 * that leaving the screen (parking) and session restore both keep it. The state is created
 * on first composition, because its defaults depend on the window; a restored layout that
 * arrives before that replaces the defaults, one that arrives after is applied in place.
 */
class WorkspaceLayoutHolder {
    private var current by mutableStateOf<WorkspaceStageState?>(null)
    private var restored: LayoutSnapshot? = null

    fun stateFor(windowSize: WindowSize): WorkspaceStageState = current ?: run {
        val snapshot = restored
        val created = if (snapshot != null) {
            WorkspaceStageState(snapshot.left, snapshot.right, snapshot.bottom, sidePanelOf(snapshot.sidePanel))
        } else {
            WorkspaceStageState(
                leftVisible = windowSize.width.isExpanded,
                rightVisible = false,
                bottomVisible = windowSize.width.atLeastMedium && !windowSize.height.isCompact,
            )
        }
        created.also { current = it }
    }

    fun restore(snapshot: LayoutSnapshot) {
        restored = snapshot
        current?.setLayout(snapshot.left, snapshot.right, snapshot.bottom, sidePanelOf(snapshot.sidePanel))
    }

    /** Null until the screen has composed once: there is no layout to save before then. */
    fun snapshot(): LayoutSnapshot? = current?.let { LayoutSnapshot(it.leftVisible, it.rightVisible, it.bottomVisible, it.sidePanel.name) }

    private fun sidePanelOf(name: String): SidePanel = SidePanel.entries.firstOrNull { it.name == name } ?: SidePanel.EXPLORER
}

/**
 * The layout for [windowSize], kept by [holder]. Defaults chosen per width class: an expanded
 * tablet shows the full IDE layout, a compact one starts with just the editor.
 */
@Composable
fun rememberWorkspaceStageState(windowSize: WindowSize, holder: WorkspaceLayoutHolder): WorkspaceStageState {
    val state = holder.stateFor(windowSize)

    // Re-run when the window changes (rotation, split-screen resize) so a
    // layout that no longer fits collapses instead of overflowing.
    LaunchedEffect(windowSize.width) {
        state.applyWidthConstraints(windowSize.width)
    }

    return state
}

/** What the left stage is showing. The rail switches between these. */
enum class SidePanel { EXPLORER, SOURCE_CONTROL }
