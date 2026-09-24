package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R

/** The live part of Home: Resume, Running, Environment. */
@Composable
internal fun NowSections(state: HomeUiState, callbacks: HomeCallbacks, nowMs: Long, resumeSelected: Boolean) {
    val onStop = rememberStopRequest(callbacks)
    Column {
        ResumeSection(state, nowMs, resumeSelected) { callbacks.onOpenProject(it, false) }
        RunningSection(
            title = stringResource(R.string.home_section_running),
            items = state.running,
            onAttach = { item -> attachRunning(state, callbacks, item) },
            onStop = onStop,
        )
        EnvironmentSection(
            state = state,
            onOpenSettings = { callbacks.onOpenDocument(HomeMetrics.SANDBOX_SETTINGS_URI) },
            onInstallLinux = callbacks.onInstallLinux,
        )
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
