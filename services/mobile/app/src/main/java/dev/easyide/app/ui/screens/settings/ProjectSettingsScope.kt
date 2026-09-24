package dev.easyide.app.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.AppContainer
import dev.easyide.app.data.settings.SettingsQuery
import dev.easyide.app.data.settings.TrustState
import dev.easyide.app.ui.foundation.LocalSettings
import kotlinx.coroutines.launch

/**
 * Resolves settings for one open workspace - environment and project layers
 * on top of the user's (PLT-04: a project value wins, edits apply live) - and
 * asks for project trust when the project file carries exec-bearing values
 * nobody has decided on yet (CUS-11).
 */
@Composable
fun ProjectSettingsScope(container: AppContainer, projectId: String, environmentId: String, content: @Composable () -> Unit) {
    val outer = LocalSettings.current
    val snapshotFlow = remember(projectId, environmentId) {
        container.settingsStore.snapshot(SettingsQuery(envId = environmentId, projectId = projectId))
    }
    val snapshot by snapshotFlow.collectAsStateWithLifecycle(initialValue = outer)
    val trustFlow = remember(projectId) { container.settingsStore.trustRequest(projectId) }
    val trust by trustFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(LocalSettings provides snapshot) { content() }

    trust?.takeIf { it.state == TrustState.PENDING }?.let { request ->
        ProjectTrustDialog(
            request = request,
            onAllow = { scope.launch { container.projectTrust.allow(request.projectId, request.fingerprint) } },
            onNotNow = { container.projectTrust.defer(request.projectId, request.fingerprint) },
            onNever = { scope.launch { container.projectTrust.deny(request.projectId, request.fingerprint) } },
        )
    }
}
