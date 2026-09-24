package dev.easyide.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import dev.easyide.app.diagnostics.CrashReportFormat
import dev.easyide.app.diagnostics.CrashReportRef
import dev.easyide.app.diagnostics.DiagnosticsReportText
import dev.easyide.app.diagnostics.DiagnosticsSharing
import dev.easyide.app.ui.screens.diagnostics.CrashRecoveryDialog
import dev.easyide.app.ui.screens.diagnostics.shareChooser
import dev.easyide.app.AppContainer
import dev.easyide.app.data.settings.WorkspaceSettingsSchema
import dev.easyide.app.ui.screens.workspace.WorkspaceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The workspace of [projectId], taken from the app-scoped registry instead of the navigation
 * entry: leaving the screen parks it (its shells and buffers stay alive), coming back
 * re-attaches the same one. See docs/decision/0023-workspace-session-lifetime.md.
 *
 * [onEnded] runs when the workspace stops existing while this screen is showing it: it was
 * closed, or ended in the background (the environment changed, the project was deleted).
 */
@Composable
fun rememberWorkspace(container: AppContainer, projectId: String, environmentId: String, onEnded: () -> Unit): WorkspaceViewModel {
    val registry = container.workspaces
    val handle = remember(projectId, environmentId) { registry.obtain(projectId, environmentId) }
    DisposableEffect(handle) {
        val lease = registry.attach(projectId)
        onDispose { lease?.let { registry.park(projectId, it) } }
    }
    val alive = projectId in registry.liveIds.collectAsStateWithLifecycle().value
    LaunchedEffect(alive) { if (!alive) onEnded() }
    return handle.viewModel
}

/**
 * On a cold launch with `workspace.openLastProjectOnLaunch`, goes straight to the project
 * used last, with Home underneath so Back still leads to the project list. Once per launch:
 * the flag survives an activity recreation, and a restored back stack already holds
 * whatever the user was in.
 *
 * Skipped while a crash report waits for acknowledgement: a project that crashed the app
 * must not be re-entered automatically, or a crash on open would loop. The recovery dialog
 * offers "reopen" instead.
 */
@Composable
fun LaunchRedirect(container: AppContainer, navController: NavHostController, startAtOnboarding: Boolean) {
    var handled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (handled || startAtOnboarding) return@LaunchedEffect
        handled = true
        if (withContext(Dispatchers.IO) { container.crashReports.pending() } != null) return@LaunchedEffect
        if (!container.settingsStore.snapshot.first()[WorkspaceSettingsSchema.openLastProjectOnLaunch]) return@LaunchedEffect
        val last = container.projectManager.projects.first().maxByOrNull { it.lastOpenedAtEpochMs } ?: return@LaunchedEffect
        navController.navigate(Destination.Workspace.routeFor(last.id))
    }
}

/**
 * On the first launch after an uncaught exception, says so and offers to reopen the last
 * project or share the log. Any answer acknowledges the report (it moves to the "seen" list
 * the Diagnostics screen still shows), so the dialog appears once per crash.
 */
@Composable
fun CrashRecovery(container: AppContainer, navController: NavHostController, startAtOnboarding: Boolean) {
    var crash by remember { mutableStateOf<Pair<CrashReportRef, String>?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (startAtOnboarding) return@LaunchedEffect
        crash = withContext(Dispatchers.IO) {
            val ref = container.crashReports.pending() ?: return@withContext null
            try {
                ref to CrashReportFormat.headline(container.crashReports.read(ref))
            } catch (gone: IOException) {
                null // deleted between listing and reading (Diagnostics > clear logs): nothing to show
            }
        }
    }
    val (ref, summary) = crash ?: return
    val acknowledge = {
        crash = null
        scope.launch(Dispatchers.IO) { container.crashReports.acknowledge(ref) }
        Unit
    }
    val lastProject = container.projectManager.projects.collectAsStateWithLifecycle(initialValue = emptyList()).value
        .maxByOrNull { it.lastOpenedAtEpochMs }
    CrashRecoveryDialog(
        reportSummary = summary,
        onReopenLastProject = lastProject?.let { project ->
            {
                acknowledge()
                navController.navigate(Destination.Workspace.routeFor(project.id))
            }
        },
        onShare = {
            scope.launch {
                val text = DiagnosticsSharing(container.appLog, container.crashReports)
                    .text(DiagnosticsReportText.header(container.buildInfo))
                context.startActivity(shareChooser(context, text))
                acknowledge()
            }
        },
        onDismiss = { acknowledge() },
    )
}
