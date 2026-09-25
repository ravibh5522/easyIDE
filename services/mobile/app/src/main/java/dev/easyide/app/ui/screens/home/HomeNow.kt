package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R

/**
 * The live part of Home: Resume, Running, Environment. [flat] is the side-panel form. With
 * [twoColumns] (the stage of a wide window) Resume and Running are the left column and Environment
 * with the optional [summary] the right one, on one grid: both columns start on the same line and
 * their gutters are the sections' own, so the outer edges line up with the page. Otherwise it is
 * one column, in the same order.
 */
@Composable
internal fun NowSections(
    state: HomeUiState,
    callbacks: HomeCallbacks,
    nowMs: Long,
    resumeSelected: Boolean,
    flat: Boolean,
    twoColumns: Boolean = false,
    summary: (@Composable () -> Unit)? = null,
) {
    val onStop = rememberStopRequest(callbacks)
    val resume = @Composable { ResumeSection(state, nowMs, resumeSelected, flat) { callbacks.onOpenProject(it, false) } }
    val running = @Composable {
        RunningSection(
            title = stringResource(R.string.home_section_running),
            items = state.running,
            flat = flat,
            onAttach = { item -> attachRunning(state, callbacks, item) },
            onStop = onStop,
        )
    }
    val environment = @Composable {
        EnvironmentSection(
            state = state,
            flat = flat,
            onOpenSettings = { callbacks.onOpenDocument(HomeMetrics.SANDBOX_SETTINGS_URI) },
            onInstallLinux = callbacks.onInstallLinux,
        )
    }
    if (twoColumns) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) { resume(); running() }
            Column(Modifier.weight(1f)) { environment(); summary?.invoke() }
        }
    } else {
        Column {
            resume()
            running()
            environment()
            summary?.invoke()
        }
    }
}

/**
 * The Stop button's behaviour, or null when the owner wired none. Stopping a workspace session ends
 * its shells and drops unsaved buffers, so it asks first (and shows that question itself); a
 * language server just stops.
 */
@Composable
internal fun rememberStopRequest(callbacks: HomeCallbacks): ((RunningItem) -> Unit)? {
    var asking by remember { mutableStateOf<RunningItem?>(null) }
    val stop = callbacks.onStop ?: return null
    asking?.let { item ->
        StopSessionDialog(
            name = item.subject,
            onConfirm = {
                asking = null
                stop(item.id)
            },
            onDismiss = { asking = null },
        )
    }
    return { item -> if (item.kind == RunningKind.SESSION) asking = item else stop(item.id) }
}

/** Attaching goes where the thing lives: its project, or for an install the screen that shows it. */
internal fun attachRunning(state: HomeUiState, callbacks: HomeCallbacks, item: RunningItem) {
    val project = item.projectId?.let(state::find)
    when {
        project != null -> callbacks.onOpenProject(project, false)
        item.kind == RunningKind.INSTALL -> callbacks.onInstallLinux()
    }
}
