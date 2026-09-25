package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitProgress

/** Shown while the project list is still loading, before the workspace can start. */
@Composable
fun WorkspaceLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        KitProgress(fraction = null)
    }
}

/**
 * The route's project id matches no record (deleted, or a stale back stack).
 * Without this the workspace waited for a record that never arrives.
 */
@Composable
fun ProjectNotFound(onBackHome: () -> Unit) {
    KitEmptyState(
        art = EmptyArt.Search,
        message = stringResource(R.string.workspace_project_not_found),
        modifier = Modifier.fillMaxSize(),
        action = KitAction(stringResource(R.string.workspace_back_home), onBackHome),
    )
}
