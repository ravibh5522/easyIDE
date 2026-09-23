package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.components.EmptyState

/** Shown while the project list is still loading, before the workspace can start. */
@Composable
fun WorkspaceLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * The route's project id matches no record (deleted, or a stale back stack).
 * Without this the workspace waited for a record that never arrives.
 */
@Composable
fun ProjectNotFound(onBackHome: () -> Unit) {
    EmptyState(
        icon = Icons.Filled.FolderOff,
        title = stringResource(R.string.workspace_project_not_found_title),
        body = stringResource(R.string.workspace_project_not_found_body),
        modifier = Modifier.fillMaxSize(),
        action = {
            Button(onClick = onBackHome) { Text(stringResource(R.string.workspace_back_home)) }
        },
    )
}
