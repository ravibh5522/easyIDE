package dev.easyide.app.ui.screens.workspace.session

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.session.ExternalState
import dev.easyide.app.ui.screens.workspace.EditorTab
import dev.easyide.app.ui.screens.workspace.NoticeBar

/**
 * Asks what to do about the first open tab whose file changed on disk while it holds unsaved
 * edits. "Decide later" hides that particular disk version for this screen; the tab stays
 * flagged and saving stays blocked, and a further outside change asks again.
 */
@Composable
fun ExternalChangeDialog(tabs: List<EditorTab>, onResolve: (path: String, keepMine: Boolean) -> Unit) {
    var deferred by remember { mutableStateOf(emptySet<Pair<String, Int>>()) }
    val pending = tabs.firstNotNullOfOrNull { tab ->
        val conflict = tab.externalState as? ExternalState.Conflict
        if (conflict != null && (tab.relativePath to conflict.diskText.hashCode()) !in deferred) tab to conflict else null
    } ?: return
    val (tab, conflict) = pending
    val path = tab.relativePath

    AlertDialog(
        onDismissRequest = { deferred = deferred + (path to conflict.diskText.hashCode()) },
        title = { Text(stringResource(R.string.session_conflict_title, tab.name)) },
        text = { Text(stringResource(R.string.session_conflict_body)) },
        confirmButton = {
            TextButton(onClick = { onResolve(path, true) }) { Text(stringResource(R.string.session_conflict_keep_mine)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { deferred = deferred + (path to conflict.diskText.hashCode()) }) {
                    Text(stringResource(R.string.session_conflict_later))
                }
                TextButton(onClick = { onResolve(path, false) }) { Text(stringResource(R.string.session_conflict_use_disk)) }
            }
        },
    )
}

/** Tells the user why a tab's save is blocked or what saving will do; nothing when the tab matches the disk. */
@Composable
fun ExternalStateNotice(state: ExternalState) {
    when (state) {
        ExternalState.InSync -> Unit
        is ExternalState.Conflict -> NoticeBar(stringResource(R.string.session_notice_conflict))
        ExternalState.Gone -> NoticeBar(stringResource(R.string.session_notice_gone))
    }
}
