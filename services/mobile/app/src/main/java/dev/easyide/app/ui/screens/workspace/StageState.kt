package dev.easyide.app.ui.screens.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
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
) {
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

    fun applyWidthConstraints(width: WidthClass) {
        if (width == WidthClass.COMPACT && leftVisible && rightVisible) {
            rightVisible = false
        }
    }
}

/**
 * Defaults chosen per width class: an expanded tablet shows the full IDE
 * layout, a compact one starts with just the editor.
 */
@Composable
fun rememberWorkspaceStageState(windowSize: WindowSize): WorkspaceStageState {
    val state = rememberSaveable(saver = WorkspaceStageStateSaver) {
        WorkspaceStageState(
            leftVisible = windowSize.width.isExpanded,
            rightVisible = false,
            bottomVisible = windowSize.width.atLeastMedium && !windowSize.height.isCompact,
        )
    }

    // Re-run when the window changes (rotation, split-screen resize) so a
    // layout that no longer fits collapses instead of overflowing.
    LaunchedEffect(windowSize.width) {
        state.applyWidthConstraints(windowSize.width)
    }

    return state
}

private val WorkspaceStageStateSaver = androidx.compose.runtime.saveable.listSaver<WorkspaceStageState, Boolean>(
    save = { listOf(it.leftVisible, it.rightVisible, it.bottomVisible) },
    restore = { WorkspaceStageState(it[0], it[1], it[2]) },
)

/** What the left stage is showing. The rail switches between these. */
enum class SidePanel { EXPLORER, SOURCE_CONTROL }
