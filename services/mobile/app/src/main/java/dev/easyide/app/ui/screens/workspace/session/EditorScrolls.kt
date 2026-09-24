package dev.easyide.app.ui.screens.workspace.session

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** Scroll offsets of one editor tab, in pixels. */
data class ScrollPos(val y: Int, val x: Int) {
    companion object {
        val ZERO = ScrollPos(0, 0)
    }
}

/**
 * The scroll offset of every open tab, hoisted out of the editor composable like
 * [dev.easyide.app.ui.screens.workspace.EditorSelections]: a parked workspace comes back
 * scrolled where it was, and the session snapshot can save it. Thread-safe because the
 * snapshot is built off the main thread; [changes] bumps on every recorded change.
 */
class EditorScrolls {
    private val positions = ConcurrentHashMap<String, ScrollPos>()
    private val version = MutableStateFlow(0L)
    val changes: StateFlow<Long> = version.asStateFlow()

    operator fun get(path: String): ScrollPos = positions[path] ?: ScrollPos.ZERO

    fun record(path: String, position: ScrollPos) {
        if (positions.put(path, position) != position) version.update { it + 1 }
    }


    fun rename(from: String, to: String) {
        positions.remove(from)?.let { positions[to] = it }
    }
}

/** The two scroll states of an editable surface. */
class EditorScrollStates(val vertical: ScrollState, val horizontal: ScrollState)

/**
 * Scroll states for the tab at [path], starting where [scrolls] last saw it and writing back
 * once scrolling settles and when the tab leaves composition (a frame-by-frame write would
 * only churn the session snapshot). One pair per tab, so switching tabs no longer carries
 * one tab's offset onto another.
 */
@Composable
fun rememberEditorScrollStates(path: String, scrolls: EditorScrolls): EditorScrollStates {
    val states = remember(path) {
        val start = scrolls[path]
        EditorScrollStates(ScrollState(start.y), ScrollState(start.x))
    }
    LaunchedEffect(states) {
        snapshotFlow {
            if (states.vertical.isScrollInProgress || states.horizontal.isScrollInProgress) null
            else ScrollPos(states.vertical.value, states.horizontal.value)
        }.filterNotNull().collect { scrolls.record(path, it) }
    }
    DisposableEffect(states) {
        onDispose { scrolls.record(path, ScrollPos(states.vertical.value, states.horizontal.value)) }
    }
    return states
}
